// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Ignacio Rodríguez Caro
package io.github.rnachoc.aduana.cli.attacks;

import com.sun.net.httpserver.HttpServer;
import io.github.rnachoc.aduana.audit.ChainVerifier;
import io.github.rnachoc.aduana.audit.Checkpoints;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Runs attack cases against a gateway. Every case gets a fresh agent (and, on request, a second
 * one), so counters, taint and approvals never leak from one case into another.
 *
 * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
 */
public final class AttackRunner {

  private static final JsonMapper JSON = JsonMapper.builder().build();
  private static final SecureRandom RANDOM = new SecureRandom();
  private static final String STANDARD_META =
      "\"_meta\":{\"io.modelcontextprotocol/protocolVersion\":\"2026-07-28\","
          + "\"io.modelcontextprotocol/clientCapabilities\":{},"
          + "\"io.modelcontextprotocol/clientInfo\":{\"name\":\"aduana-attacks\",\"version\":\"0.1.0\"}}";

  private final URI gateway;
  private final Settings settings;
  private final String run = HexFormat.of().formatHex(RANDOM.generateSeed(4));
  private final String password = "atk-" + HexFormat.of().formatHex(RANDOM.generateSeed(12));
  private final Map<String, Session> users = new HashMap<>();
  private final Map<String, String> stubDescriptions = new ConcurrentHashMap<>();
  private HttpServer stub;
  private Session admin;
  private int stubs;

  /**
   * Where the runner reaches the gateway, its database and itself.
   *
   * @param adminEmail the bootstrap administrator
   * @param adminPassword its password
   * @param databaseUrl JDBC URL of the gateway's database, for the tampering steps
   * @param databaseUser the database user
   * @param databasePassword its password
   * @param stubHost the name the gateway uses to reach this runner's poisoned MCP server
   * @param stubPort the port that server listens on
   * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
   */
  public record Settings(
      String adminEmail,
      String adminPassword,
      String databaseUrl,
      String databaseUser,
      String databasePassword,
      String stubHost,
      int stubPort) {}

  /**
   * What one case has learned so far.
   *
   * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
   */
  private final class Context {
    private final AttackCase attack;
    private String agentId;
    private String agentKey;
    private String otherKey;
    private String approvalId = "";
    private String server = "";
    private String toolId = "";

    /**
     * Starts a case.
     *
     * @param attack the case
     * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
     */
    Context(AttackCase attack) {
      this.attack = attack;
    }
  }

  /**
   * Creates a runner.
   *
   * @param gateway the gateway's base URL
   * @param settings credentials and addresses
   * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
   */
  public AttackRunner(URI gateway, Settings settings) {
    this.gateway = gateway;
    this.settings = settings;
  }

  /**
   * Runs every case and returns the results.
   *
   * @param cases the cases
   * @param log where to print progress
   * @return one result per case
   * @throws IOException if the gateway cannot be reached for setup
   * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
   */
  public List<CaseResult> run(List<AttackCase> cases, PrintWriter log) throws IOException {
    admin = new Session(gateway, null).login(settings.adminEmail(), settings.adminPassword());
    for (String[] user :
        new String[][] {
          {"finance", "approver", "finance"},
          {"security", "approver", "security"},
          {"lead", "approver", "support-leads"},
          {"viewer", "viewer", ""}
        }) {
      String email = "atk-" + run + "-" + user[0] + "@example.com";
      ObjectNode body =
          JSON.createObjectNode()
              .put("email", email)
              .put("password", password)
              .put("role", user[1]);
      if (!user[2].isEmpty()) {
        body.putArray("groups").add(user[2]);
      }
      check(
          admin.send("POST", "/api/v1/users", body.toString(), Map.of()), "create user " + user[0]);
      users.put(user[0], new Session(gateway, null).login(email, password));
    }
    users.put("admin", admin);
    startStub();
    List<CaseResult> results = new ArrayList<>();
    try {
      for (AttackCase attack : cases) {
        CaseResult result = runCase(attack);
        log.printf(
            "%-8s %-13s %s%n",
            attack.id(),
            result.stopped() ? "stopped" : "NOT STOPPED",
            String.join("; ", result.observed()));
        log.flush();
        results.add(result);
      }
    } finally {
      stub.stop(0);
    }
    return results;
  }

  /**
   * Runs one case.
   *
   * @param attack the case
   * @return its result; a step that throws counts as not stopped
   * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
   */
  private CaseResult runCase(AttackCase attack) {
    Context context = new Context(attack);
    List<String> observed = new ArrayList<>();
    boolean stopped = true;
    try {
      newAgent(context, JSON.createObjectNode().put("team", "support").put("env", "demo"));
      for (JsonNode step : attack.steps()) {
        Outcome outcome = step(context, step);
        if (step.has("expect")) {
          boolean met = Expectations.met(expand(context, step.get("expect")), outcome);
          stopped &= met;
          observed.add(describe(step.get("expect"), outcome));
        }
      }
    } catch (IOException | RuntimeException | SQLException exception) {
      stopped = false;
      observed.add("runner error: " + exception.getClass().getSimpleName());
    }
    return new CaseResult(attack, stopped, observed);
  }

  /**
   * Describes an outcome for the report. A tampering step names the first expected problem kind the
   * verifier found, since which other kinds appear depends on where checkpoints fall.
   *
   * @param expect the step's expectation
   * @param outcome what happened
   * @return a description that does not change between runs
   * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
   */
  private static String describe(JsonNode expect, Outcome outcome) {
    if (expect.has("problem") && !outcome.problems().isEmpty()) {
      for (JsonNode kind : expect.get("problem")) {
        if (outcome.problems().contains(kind.asString())) {
          return kind.asString();
        }
      }
    }
    return outcome.describe();
  }

  /**
   * Runs one step.
   *
   * @param context the case so far
   * @param step the step
   * @return what it observed
   * @throws IOException if the gateway cannot be reached
   * @throws SQLException if the database cannot be reached
   * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
   */
  private Outcome step(Context context, JsonNode step) throws IOException, SQLException {
    String kind = AttackCase.kind(step);
    return switch (kind) {
      case "agent" -> {
        newAgent(context, (ObjectNode) step.get("agent"));
        yield Outcome.code("OK");
      }
      case "call" -> call(context, step);
      case "raw" -> raw(context, step);
      case "http" -> http(context, step);
      case "approve", "reject" -> {
        Session session = users.get(step.path("as").asString("finance"));
        Session.Response response =
            session.send(
                "POST", "/api/v1/approvals/" + context.approvalId + "/" + kind, "{}", Map.of());
        yield Outcome.status(response.status());
      }
      case "sql" -> sql(step.get("sql").asString());
      case "tamper" -> tamper(Tampering.valueOf(step.get("tamper").asString()));
      case "log" -> log(context, step.get("log").asString());
      case "stub" -> stub(context, step.get("stub"));
      case "stub-change" -> {
        stubDescriptions.put(context.server, step.get("stub-change").asString());
        yield sync(context);
      }
      case "list" -> {
        ObjectNode params = JSON.createObjectNode();
        Session.Response response = mcp(context.agentKey, "tools/list", params, Map.of());
        yield new Outcome(
            "HTTP_" + response.status(),
            response.status(),
            List.of(),
            List.of(),
            List.of(),
            response.body());
      }
      case "parallel", "repeat" -> many(context, step, kind.equals("parallel"));
      case "wait" -> {
        try {
          Thread.sleep(step.get("wait").asLong());
        } catch (InterruptedException exception) {
          Thread.currentThread().interrupt();
        }
        yield Outcome.code("OK");
      }
      default -> throw new IllegalArgumentException("unknown step " + kind);
    };
  }

  /**
   * Creates the case's agent with the given attributes and an API key.
   *
   * @param context the case
   * @param attributes the agent's attributes
   * @throws IOException if the gateway refuses
   * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
   */
  private void newAgent(Context context, ObjectNode attributes) throws IOException {
    String[] agent =
        createAgent(context.attack.id().toLowerCase() + "-" + run + "-" + stubs++, attributes);
    context.agentId = agent[0];
    context.agentKey = agent[1];
  }

  /**
   * Creates an agent principal and a key.
   *
   * @param name the principal's name
   * @param attributes its attributes
   * @return its id and the key's secret
   * @throws IOException if the gateway refuses
   * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
   */
  private String[] createAgent(String name, ObjectNode attributes) throws IOException {
    ObjectNode body = JSON.createObjectNode().put("kind", "agent").put("name", "atk-" + name);
    body.set("attributes", attributes);
    Session.Response created =
        check(admin.send("POST", "/api/v1/principals", body.toString(), Map.of()), "create agent");
    String id = JSON.readTree(created.body()).path("id").asString();
    Session.Response key =
        check(
            admin.send("POST", "/api/v1/principals/" + id + "/keys", "{}", Map.of()), "create key");
    return new String[] {id, JSON.readTree(key.body()).path("secret").asString()};
  }

  /**
   * Calls a tool as the case's agent, or with another key.
   *
   * @param context the case
   * @param step the step: `call`, `args`, `meta`, `headers`, `key` (own, other, revoked, invalid,
   *     none), `trace`
   * @return the code the agent got
   * @throws IOException if the gateway cannot be reached
   * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
   */
  private Outcome call(Context context, JsonNode step) throws IOException {
    ObjectNode params = JSON.createObjectNode();
    params.put("name", step.get("call").asString().replace("${server}", context.server));
    params.set(
        "arguments",
        expand(
            context,
            step.path("args").isMissingNode() ? JSON.createObjectNode() : step.get("args")));
    ObjectNode meta = JSON.createObjectNode();
    if (step.has("meta")) {
      meta.setAll((ObjectNode) expand(context, step.get("meta")));
    }
    if (step.has("trace")) {
      meta.put("traceparent", "00-" + step.get("trace").asString() + "-00f067aa0ba902b7-01");
    }
    params.set("_meta", meta);
    Map<String, String> headers = new LinkedHashMap<>();
    step.path("headers")
        .properties()
        .forEach(e -> headers.put(e.getKey(), e.getValue().asString()));
    Session.Response response =
        mcp(key(context, step.path("key").asString("own")), "tools/call", params, headers);
    return interpret(context, response);
  }

  /**
   * Picks the bearer key a call presents.
   *
   * @param context the case
   * @param which own, other, revoked, invalid or none
   * @return the key, or null for none
   * @throws IOException if a key cannot be created
   * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
   */
  private String key(Context context, String which) throws IOException {
    return switch (which) {
      case "none" -> null;
      case "invalid" -> "adn_" + context.agentKey.substring(4, 12) + "x".repeat(32);
      case "other" -> {
        if (context.otherKey == null) {
          context.otherKey =
              createAgent(
                  context.attack.id().toLowerCase() + "-" + run + "-other" + stubs++,
                  JSON.createObjectNode().put("team", "finance").put("env", "demo"))[1];
        }
        yield context.otherKey;
      }
      case "revoked" -> {
        Session.Response keys =
            admin.send("GET", "/api/v1/principals/" + context.agentId + "/keys", null, Map.of());
        for (JsonNode key : JSON.readTree(keys.body())) {
          admin.send("DELETE", "/api/v1/keys/" + key.path("id").asString(), null, Map.of());
        }
        yield context.agentKey;
      }
      default -> context.agentKey;
    };
  }

  /**
   * Posts a raw body to `/mcp` with the agent's key unless headers say otherwise.
   *
   * @param context the case
   * @param step the step: `raw` (the body; `${big:N}` repeats a character N times), `headers`
   * @return the code
   * @throws IOException if the gateway cannot be reached
   * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
   */
  private Outcome raw(Context context, JsonNode step) throws IOException {
    String body = step.get("raw").asString();
    java.util.regex.Matcher big =
        java.util.regex.Pattern.compile("\\$\\{big:(\\d+)}").matcher(body);
    if (big.find()) {
      body = big.replaceAll("a".repeat(Integer.parseInt(big.group(1))));
    }
    body = body.replace("${nest}", "[".repeat(5000) + "]".repeat(5000));
    body = body.replace("${meta}", STANDARD_META);
    Map<String, String> headers = new LinkedHashMap<>();
    headers.put("Accept", "application/json, text/event-stream");
    headers.put("MCP-Protocol-Version", "2026-07-28");
    headers.put("Content-Type", "application/json");
    step.path("headers")
        .properties()
        .forEach(e -> headers.put(e.getKey(), e.getValue().asString()));
    Session session = new Session(gateway, context.agentKey);
    return interpret(context, sendRaw(session, body, headers));
  }

  /**
   * Sends a raw MCP POST, turning a refused body into an outcome rather than an exception.
   *
   * @param session the client
   * @param body the body
   * @param headers the headers
   * @return the response
   * @throws IOException if the gateway cannot be reached
   * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
   */
  private static Session.Response sendRaw(Session session, String body, Map<String, String> headers)
      throws IOException {
    Map<String, String> copy = new LinkedHashMap<>(headers);
    copy.remove("Content-Type");
    return session.send("POST", "/mcp", body, copy);
  }

  /**
   * Sends a 2026-07-28 request to `/mcp`.
   *
   * @param key the bearer key, or null
   * @param method the JSON-RPC method
   * @param params the parameters; protocol `_meta` keys are added when absent
   * @param headers extra headers
   * @return the response
   * @throws IOException if the gateway cannot be reached
   * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
   */
  private Session.Response mcp(
      String key, String method, ObjectNode params, Map<String, String> headers)
      throws IOException {
    ObjectNode meta =
        params.has("_meta") ? (ObjectNode) params.get("_meta") : params.putObject("_meta");
    if (!meta.has("io.modelcontextprotocol/protocolVersion")) {
      meta.put("io.modelcontextprotocol/protocolVersion", "2026-07-28");
    }
    meta.putObject("io.modelcontextprotocol/clientCapabilities");
    meta.putObject("io.modelcontextprotocol/clientInfo")
        .put("name", "aduana-attacks")
        .put("version", "0.1.0");
    ObjectNode body =
        JSON.createObjectNode().put("jsonrpc", "2.0").put("id", 1).put("method", method);
    body.set("params", params);
    Map<String, String> all = new LinkedHashMap<>();
    all.put("Accept", "application/json, text/event-stream");
    all.put("MCP-Protocol-Version", "2026-07-28");
    all.put("Mcp-Method", method);
    if (params.path("name").isString()) {
      all.put("Mcp-Name", params.get("name").asString());
    }
    all.putAll(headers);
    return new Session(gateway, key).send("POST", "/mcp", body.toString(), all);
  }

  /**
   * Reads the agent-facing code from an MCP response and remembers an approval id.
   *
   * @param context the case
   * @param response the response
   * @return the outcome
   * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
   */
  private static Outcome interpret(Context context, Session.Response response) {
    JsonNode body;
    try {
      body = JSON.readTree(response.body());
    } catch (JacksonException exception) {
      return new Outcome(
          "HTTP_" + response.status(),
          response.status(),
          List.of(),
          List.of("HTTP_" + response.status()),
          List.of(),
          response.body());
    }
    String code;
    List<String> flags = new ArrayList<>();
    if (body.has("error")) {
      String data = body.path("error").path("data").path("code").asString("");
      code = data.isEmpty() ? "RPC_" + body.path("error").path("code").asInt() : data;
    } else if (body.has("result")) {
      JsonNode result = body.get("result");
      code =
          result.path("isError").asBoolean()
              ? result.path("structuredContent").path("code").asString("FORWARDED_TOOL_ERROR")
              : "OK";
      result.path("_meta").path("aduana/flags").forEach(flag -> flags.add(flag.asString()));
      String approval = result.path("structuredContent").path("approval_id").asString("");
      if (!approval.isEmpty()) {
        context.approvalId = approval;
      }
    } else {
      code = "HTTP_" + response.status();
    }
    if (response.status() != 200 && !body.has("error") && !body.has("result")) {
      code = "HTTP_" + response.status();
    }
    return new Outcome(code, response.status(), List.of(), List.of(code), flags, response.body());
  }

  /**
   * Calls the administration API as a console user, the agent, or nobody.
   *
   * @param context the case
   * @param step the step: `http` ("METHOD /path"), `as`, `body`, `csrf` (false to leave the token
   *     out)
   * @return the status
   * @throws IOException if the gateway cannot be reached
   * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
   */
  private Outcome http(Context context, JsonNode step) throws IOException {
    String[] line = step.get("http").asString().split(" ", 2);
    String as = step.path("as").asString("anonymous");
    Session session =
        switch (as) {
          case "agent" -> new Session(gateway, context.agentKey);
          case "anonymous" -> new Session(gateway, null);
          default -> users.get(as);
        };
    Map<String, String> headers = new LinkedHashMap<>();
    if (!step.path("csrf").asBoolean(true)) {
      headers.put("X-No-Csrf", "1");
    }
    step.path("headers")
        .properties()
        .forEach(e -> headers.put(e.getKey(), e.getValue().asString()));
    String path =
        line[1]
            .replace("${approval_id}", context.approvalId)
            .replace("${agent_id}", context.agentId)
            .replace("${tool_id}", context.toolId)
            .replace("${server}", context.server);
    String body =
        step.has("body")
            ? expand(context, step.get("body")).toString()
            : (line[0].equals("GET") ? null : "{}");
    Session.Response response = session.send(line[0], path, body, headers);
    return new Outcome(
        "HTTP_" + response.status(),
        response.status(),
        List.of(),
        List.of("HTTP_" + response.status()),
        List.of(),
        response.body());
  }

  /**
   * Runs a statement as the gateway's application role.
   *
   * @param statement the SQL
   * @return `SQL_ERROR` when the database refuses, `OK` when it runs
   * @throws SQLException if the database cannot be reached
   * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
   */
  private Outcome sql(String statement) throws SQLException {
    try (Connection connection =
            DriverManager.getConnection(
                settings.databaseUrl(), settings.databaseUser(), settings.databasePassword());
        Statement sql = connection.createStatement()) {
      sql.execute("set role aduana_app");
      try {
        sql.execute(statement);
        return Outcome.code("OK");
      } catch (SQLException refused) {
        return Outcome.code("SQL_ERROR");
      }
    }
  }

  /**
   * Exports the log, tampers with the copy and verifies it with the published key.
   *
   * @param tampering what to do
   * @return the problems the verifier found
   * @throws IOException if the export fails
   * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
   */
  private Outcome tamper(Tampering tampering) throws IOException {
    List<JsonNode> entries =
        lines(check(admin.send("GET", "/api/v1/audit/export", null, Map.of()), "export").body());
    List<JsonNode> checkpoints =
        lines(
            check(
                    admin.send("GET", "/api/v1/audit/export?checkpoints=true", null, Map.of()),
                    "export")
                .body());
    String keyText =
        check(admin.send("GET", "/.well-known/aduana-audit-key", null, Map.of()), "key").body();
    PublicKey key =
        Checkpoints.publicKey(
            Base64.getDecoder().decode(JSON.readTree(keyText).path("spki").asString()));
    Tampering.Tampered tampered = tampering.apply(entries, checkpoints);
    ChainVerifier.Report report =
        ChainVerifier.verify(tampered.entries(), tampered.checkpoints(), Optional.of(key));
    return Outcome.problems(report.problems().stream().map(ChainVerifier.Problem::kind).toList());
  }

  /**
   * Reads the newest log entry of a type written for the case's agent.
   *
   * @param context the case
   * @param type the entry type
   * @return the entry's decision as code, its flags, and its JSON as text
   * @throws IOException if the log cannot be read
   * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
   */
  private Outcome log(Context context, String type) throws IOException {
    JsonNode verify =
        JSON.readTree(
            check(admin.send("GET", "/api/v1/audit/verify", null, Map.of()), "verify").body());
    long from = Math.max(1, verify.path("lastSeq").asLong() - 199);
    JsonNode entries =
        JSON.readTree(
            check(
                    admin.send(
                        "GET",
                        "/api/v1/audit?limit=200&from_seq="
                            + from
                            + "&principal="
                            + context.agentId,
                        null,
                        Map.of()),
                    "audit")
                .body());
    JsonNode newest = null;
    for (JsonNode entry : entries) {
      if (entry.path("type").asString().equals(type)) {
        newest = entry;
      }
    }
    if (newest == null) {
      return Outcome.code("NO_ENTRY");
    }
    List<String> flags = new ArrayList<>();
    newest.path("flags").forEach(flag -> flags.add(flag.asString()));
    String code = newest.path("decision").asString(newest.path("reason_code").asString("NONE"));
    return new Outcome(code, 0, List.of(), List.of(code), flags, newest.toString());
  }

  /**
   * Registers a poisoned MCP server served by this runner and syncs it.
   *
   * @param context the case
   * @param stubStep `tool` and `description`
   * @return the tool's catalog state as code, its description marks as flags
   * @throws IOException if the gateway refuses
   * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
   */
  private Outcome stub(Context context, JsonNode stubStep) throws IOException {
    context.server = "atk" + run + "s" + stubs++;
    stubDescriptions.put(context.server, stubStep.path("description").asString());
    stubDescriptions.put(context.server + "#tool", stubStep.path("tool").asString("lookup"));
    ObjectNode body =
        JSON.createObjectNode()
            .put("name", context.server)
            .put(
                "endpoint",
                "http://"
                    + settings.stubHost()
                    + ":"
                    + settings.stubPort()
                    + "/"
                    + context.server
                    + "/mcp")
            .put("strict", true)
            .put("status", "active");
    check(admin.send("POST", "/api/v1/servers", body.toString(), Map.of()), "register stub");
    return sync(context);
  }

  /**
   * Syncs the case's stub server and reads its tool from the catalog.
   *
   * @param context the case
   * @return the tool's state and description marks
   * @throws IOException if the gateway refuses
   * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
   */
  private Outcome sync(Context context) throws IOException {
    JsonNode servers =
        JSON.readTree(
            check(admin.send("GET", "/api/v1/servers", null, Map.of()), "servers").body());
    String id = "";
    for (JsonNode server : servers) {
      if (server.path("name").asString().equals(context.server)) {
        id = server.path("id").asString();
      }
    }
    check(admin.send("POST", "/api/v1/servers/" + id + "/sync", "{}", Map.of()), "sync");
    JsonNode tools =
        JSON.readTree(check(admin.send("GET", "/api/v1/tools", null, Map.of()), "tools").body());
    for (JsonNode tool : tools) {
      if (tool.path("server").asString().equals(context.server)) {
        List<String> flags = new ArrayList<>();
        tool.path("descriptionFlags").forEach(flag -> flags.add(flag.asString()));
        context.toolId = tool.path("id").asString();
        String state = tool.path("state").asString().toUpperCase();
        return new Outcome(state, 0, List.of(), List.of(state), flags, tool.toString());
      }
    }
    return Outcome.code("NOT_IN_CATALOG");
  }

  /**
   * Repeats a call, one after another or all at once.
   *
   * @param context the case
   * @param step `parallel` or `repeat` (how many) and `call` with its fields
   * @param parallel true to send them concurrently
   * @return every code seen
   * @throws IOException if the gateway cannot be reached
   * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
   */
  private Outcome many(Context context, JsonNode step, boolean parallel) throws IOException {
    int count = step.get(parallel ? "parallel" : "repeat").asInt();
    List<String> codes = new ArrayList<>();
    if (!parallel) {
      for (int i = 0; i < count; i++) {
        codes.add(call(context, step).code());
      }
    } else {
      try (ExecutorService pool = Executors.newFixedThreadPool(count)) {
        List<Callable<String>> calls = new ArrayList<>();
        for (int i = 0; i < count; i++) {
          calls.add(() -> call(context, step).code());
        }
        for (Future<String> future : pool.invokeAll(calls)) {
          codes.add(future.get());
        }
      } catch (InterruptedException exception) {
        Thread.currentThread().interrupt();
        throw new IOException("interrupted", exception);
      } catch (java.util.concurrent.ExecutionException exception) {
        throw new IOException("a parallel call failed", exception);
      }
    }
    String last = codes.isEmpty() ? "NONE" : codes.getLast();
    return new Outcome(last, 0, List.of(), List.copyOf(codes), List.of(), "");
  }

  /**
   * Replaces `${approval_id}` and `${run}` in a JSON value.
   *
   * @param context the case
   * @param value the value
   * @return the expanded copy
   * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
   */
  private JsonNode expand(Context context, JsonNode value) {
    String text =
        value.toString().replace("${approval_id}", context.approvalId).replace("${run}", run);
    return JSON.readTree(text);
  }

  /**
   * Starts this runner's MCP server, whose tool descriptions the cases choose.
   *
   * @throws IOException if the port is taken
   * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
   */
  private void startStub() throws IOException {
    stub = HttpServer.create(new InetSocketAddress(settings.stubPort()), 0);
    stub.createContext(
        "/",
        exchange -> {
          String server = exchange.getRequestURI().getPath().split("/")[1];
          JsonNode request;
          try (InputStream in = exchange.getRequestBody()) {
            request = JSON.readTree(new String(in.readAllBytes(), StandardCharsets.UTF_8));
          }
          ObjectNode result = JSON.createObjectNode();
          if (request.path("method").asString().equals("tools/list")) {
            ObjectNode tool = result.putArray("tools").addObject();
            tool.put("name", stubDescriptions.getOrDefault(server + "#tool", "lookup"));
            tool.put("description", stubDescriptions.getOrDefault(server, "Look something up."));
            tool.putObject("inputSchema")
                .put("type", "object")
                .putObject("properties")
                .putObject("q")
                .put("type", "string");
            result.put("ttlMs", 1000).put("cacheScope", "public");
          } else {
            result.put("resultType", "complete").put("isError", false);
            result.putArray("content").addObject().put("type", "text").put("text", "done");
          }
          ObjectNode response = JSON.createObjectNode().put("jsonrpc", "2.0");
          response.set("id", request.path("id"));
          response.set("result", result);
          byte[] bytes = response.toString().getBytes(StandardCharsets.UTF_8);
          exchange.getResponseHeaders().add("Content-Type", "application/json");
          exchange.sendResponseHeaders(200, bytes.length);
          try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
          }
        });
    stub.start();
  }

  /**
   * Splits JSON Lines.
   *
   * @param text the body
   * @return one node per line
   * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
   */
  private static List<JsonNode> lines(String text) {
    List<JsonNode> nodes = new ArrayList<>();
    for (String line : text.split("\n")) {
      if (!line.isBlank()) {
        nodes.add(JSON.readTree(line));
      }
    }
    return nodes;
  }

  /**
   * Fails setup when the gateway refuses.
   *
   * @param response the response
   * @param what what was attempted
   * @return the response, when it succeeded
   * @throws IOException when the status is not 2xx
   * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
   */
  private static Session.Response check(Session.Response response, String what) throws IOException {
    if (response.status() / 100 != 2) {
      throw new IOException(what + ": HTTP " + response.status());
    }
    return response;
  }
}
