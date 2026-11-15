// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Ignacio Rodríguez Caro

import java.io.IOException;
import java.net.CookieManager;
import java.net.HttpCookie;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Measures the latency Aduana adds to a tool call (plan Phase 11): the same `get_customer` call
 * sent straight to the demo CRM server and through the gateway, at a constant rate of 50, 200 and
 * 500 requests per second, with a 50-rule policy and the log enabled.
 *
 * <p>Requests are sent on a fixed schedule whatever the answers do (open loop), and each latency is
 * measured from the moment the request was due, so a slow answer is not hidden by sending less.
 * Run with `make bench`; it writes `bench/REPORT.md` and `bench/results.csv`.
 *
 * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
 */
public final class Bench {

  private static final int[] RATES = {50, 200, 500};
  private static final Duration WARM_UP = Duration.ofSeconds(5);
  private static final Duration MEASURE = Duration.ofSeconds(30);
  private static final Duration TIMEOUT = Duration.ofSeconds(10);

  /**
   * Not instantiated.
   *
   * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
   */
  private Bench() {}

  /**
   * One measured run.
   *
   * @param target `direct` or `aduana`
   * @param rate requests per second
   * @param sent requests sent in the measured window
   * @param errors requests that failed or answered an error
   * @param p50 median latency in milliseconds
   * @param p95 95th percentile in milliseconds
   * @param p99 99th percentile in milliseconds
   * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
   */
  record Run(String target, int rate, int sent, int errors, double p50, double p95, double p99) {}

  /**
   * Runs every rate against both targets and writes the report.
   *
   * @param args unused
   * @throws Exception if the report cannot be written
   * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
   */
  public static void main(String[] args) throws Exception {
    String key = benchAgent(System.getenv().getOrDefault("ADMIN_URL", "http://gateway:8080"));
    String direct = System.getenv().getOrDefault("DIRECT_URL", "http://crm:3001/mcp");
    String gateway = System.getenv().getOrDefault("GATEWAY_URL", "http://gateway:8080/mcp");
    HttpClient http =
        HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(5))
            .executor(java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor())
            .build();
    List<Run> runs = new ArrayList<>();
    for (int rate : RATES) {
      runs.add(measure(http, "direct", rate, request(direct, "get_customer", null)));
      runs.add(measure(http, "aduana", rate, request(gateway, "crm__get_customer", key)));
    }
    writeReport(runs);
  }

  /**
   * Creates an agent for this run only, so its rate counters never touch the demo agents.
   *
   * @param base the gateway's base URL
   * @return the new agent's API key
   * @throws IOException if the gateway refuses
   * @throws InterruptedException if interrupted
   * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
   */
  private static String benchAgent(String base) throws IOException, InterruptedException {
    CookieManager cookies = new CookieManager();
    HttpClient admin = HttpClient.newBuilder().cookieHandler(cookies).build();
    String login =
        "{\"email\":\""
            + System.getenv("ADUANA_ADMIN_EMAIL")
            + "\",\"password\":\""
            + System.getenv("ADUANA_ADMIN_PASSWORD")
            + "\"}";
    send(admin, cookies, base, "POST", "/api/v1/auth/login", login);
    send(admin, cookies, base, "GET", "/api/v1/auth/me", null);
    String name = "bench-" + Long.toHexString(System.nanoTime());
    String principal =
        send(
            admin,
            cookies,
            base,
            "POST",
            "/api/v1/principals",
            "{\"kind\":\"agent\",\"name\":\""
                + name
                + "\",\"attributes\":{\"team\":\"support\",\"env\":\"demo\"}}");
    String id = field(principal, "id");
    return field(send(admin, cookies, base, "POST", "/api/v1/principals/" + id + "/keys", "{}"), "secret");
  }

  /**
   * Sends an administration request with the session's CSRF token.
   *
   * @param http the client
   * @param cookies its cookie store
   * @param base the gateway
   * @param method the method
   * @param path the path
   * @param body a JSON body, or null
   * @return the response body
   * @throws IOException if the gateway refuses
   * @throws InterruptedException if interrupted
   * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
   */
  private static String send(
      HttpClient http, CookieManager cookies, String base, String method, String path, String body)
      throws IOException, InterruptedException {
    HttpRequest.Builder request =
        HttpRequest.newBuilder(URI.create(base + path))
            .method(
                method,
                body == null
                    ? HttpRequest.BodyPublishers.noBody()
                    : HttpRequest.BodyPublishers.ofString(body));
    if (body != null) {
      request.header("Content-Type", "application/json");
    }
    for (HttpCookie cookie : cookies.getCookieStore().getCookies()) {
      if (cookie.getName().equals("XSRF-TOKEN")) {
        request.header("X-XSRF-TOKEN", cookie.getValue());
      }
    }
    HttpResponse<String> response = http.send(request.build(), HttpResponse.BodyHandlers.ofString());
    if (response.statusCode() / 100 != 2) {
      throw new IOException(method + " " + path + ": HTTP " + response.statusCode());
    }
    return response.body();
  }

  /**
   * Reads a string field from a flat JSON object.
   *
   * @param json the object
   * @param name the field
   * @return its value
   * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
   */
  private static String field(String json, String name) {
    java.util.regex.Matcher matcher =
        java.util.regex.Pattern.compile("\"" + name + "\"\\s*:\\s*\"([^\"]+)\"").matcher(json);
    if (!matcher.find()) {
      throw new IllegalStateException("no " + name + " in " + json);
    }
    return matcher.group(1);
  }

  /**
   * Builds the call.
   *
   * @param url the MCP endpoint
   * @param tool the tool name at that endpoint
   * @param key the agent key, or null for the direct server
   * @return the request
   * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
   */
  private static HttpRequest request(String url, String tool, String key) {
    String body =
        "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/call\",\"params\":{\"name\":\""
            + tool
            + "\",\"arguments\":{\"customer_id\":\"C-1001\"},\"_meta\":{"
            + "\"io.modelcontextprotocol/protocolVersion\":\"2026-07-28\","
            + "\"io.modelcontextprotocol/clientCapabilities\":{},"
            + "\"io.modelcontextprotocol/clientInfo\":{\"name\":\"aduana-bench\",\"version\":\"0.1.0\"}}}}";
    HttpRequest.Builder builder =
        HttpRequest.newBuilder(URI.create(url))
            .timeout(TIMEOUT)
            .header("Content-Type", "application/json")
            .header("Accept", "application/json, text/event-stream")
            .header("MCP-Protocol-Version", "2026-07-28")
            .header("Mcp-Method", "tools/call")
            .header("Mcp-Name", tool)
            .POST(HttpRequest.BodyPublishers.ofString(body));
    if (key != null) {
      builder.header("Authorization", "Bearer " + key);
    }
    return builder.build();
  }

  /**
   * Sends requests at a constant rate, warms up, then measures.
   *
   * @param http the client
   * @param target the target's label
   * @param rate requests per second
   * @param request the request to send
   * @return the measured run
   * @throws InterruptedException if interrupted
   * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
   */
  private static Run measure(HttpClient http, String target, int rate, HttpRequest request)
      throws InterruptedException {
    long interval = TimeUnit.SECONDS.toNanos(1) / rate;
    long start = System.nanoTime();
    long measureFrom = start + WARM_UP.toNanos();
    long end = measureFrom + MEASURE.toNanos();
    ConcurrentLinkedQueue<Long> latencies = new ConcurrentLinkedQueue<>();
    AtomicInteger errors = new AtomicInteger();
    AtomicInteger sent = new AtomicInteger();
    List<CompletableFuture<Void>> pending = new ArrayList<>();
    for (long due = start; due < end; due += interval) {
      long wait = due - System.nanoTime();
      if (wait > 0) {
        TimeUnit.NANOSECONDS.sleep(wait);
      }
      boolean counted = due >= measureFrom;
      long scheduled = due;
      if (counted) {
        sent.incrementAndGet();
      }
      pending.add(
          http.sendAsync(request, HttpResponse.BodyHandlers.ofString())
              .handle(
                  (response, failure) -> {
                    long latency = System.nanoTime() - scheduled;
                    if (counted) {
                      boolean ok =
                          failure == null
                              && response.statusCode() == 200
                              && !response.body().contains("\"isError\":true")
                              && !response.body().contains("\"error\"");
                      if (ok) {
                        latencies.add(latency);
                      } else {
                        errors.incrementAndGet();
                      }
                    }
                    return null;
                  }));
    }
    CompletableFuture.allOf(pending.toArray(CompletableFuture[]::new)).join();
    List<Long> sorted = new ArrayList<>(latencies);
    Collections.sort(sorted);
    Run run =
        new Run(
            target,
            rate,
            sent.get(),
            errors.get(),
            percentile(sorted, 50),
            percentile(sorted, 95),
            percentile(sorted, 99));
    System.out.printf(
        Locale.ROOT,
        "%-6s %3d rps: sent %d, errors %d, p50 %.1f ms, p95 %.1f ms, p99 %.1f ms%n",
        target,
        rate,
        run.sent(),
        run.errors(),
        run.p50(),
        run.p95(),
        run.p99());
    return run;
  }

  /**
   * Reads a percentile by the nearest-rank method.
   *
   * @param sorted latencies in nanoseconds, ascending
   * @param percent the percentile
   * @return milliseconds, or NaN when there are none
   * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
   */
  private static double percentile(List<Long> sorted, int percent) {
    if (sorted.isEmpty()) {
      return Double.NaN;
    }
    int rank = (int) Math.ceil(percent / 100.0 * sorted.size());
    return sorted.get(Math.max(0, rank - 1)) / 1_000_000.0;
  }

  /**
   * Writes `bench/results.csv` and `bench/REPORT.md`.
   *
   * @param runs the runs, direct and through Aduana for each rate
   * @throws IOException if a file cannot be written
   * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
   */
  private static void writeReport(List<Run> runs) throws IOException {
    StringBuilder csv = new StringBuilder("target,rate,sent,errors,p50_ms,p95_ms,p99_ms\n");
    runs.forEach(
        run ->
            csv.append(
                String.format(
                    Locale.ROOT,
                    "%s,%d,%d,%d,%.1f,%.1f,%.1f%n",
                    run.target(),
                    run.rate(),
                    run.sent(),
                    run.errors(),
                    run.p50(),
                    run.p95(),
                    run.p99())));
    Files.writeString(Path.of("bench/results.csv"), csv, StandardCharsets.UTF_8);
    StringBuilder md = new StringBuilder();
    md.append("# Latency benchmark\n\n");
    md.append("Generated by `make bench` (`bench/Bench.java`). Do not edit by hand.\n\n");
    md.append("- Date: ").append(LocalDate.now(ZoneOffset.UTC)).append('\n');
    md.append("- Machine: ").append(machine()).append('\n');
    md.append(
        "- Setup: Docker Compose on that machine; gateway, PostgreSQL, the demo CRM server and this load"
            + " generator share it. Policy: 50 rules (`bench/policy/`), log enabled with a signed"
            + " checkpoint every 20 entries, rate limits raised so they never refuse.\n");
    md.append(
        "- Method: `crm get_customer` sent straight to the demo server (direct) and as"
            + " `crm__get_customer` through Aduana, at a constant rate for "
            + MEASURE.toSeconds()
            + " s after "
            + WARM_UP.toSeconds()
            + " s of warm-up. Latency counts from the moment each request was due (open loop)."
            + " Added latency is the difference between the two runs' percentiles.\n\n");
    md.append("| Rate (req/s) | Target | Sent | Errors | p50 (ms) | p95 (ms) | p99 (ms) |\n");
    md.append("|---|---|---|---|---|---|---|\n");
    for (Run run : runs) {
      md.append(
          String.format(
              Locale.ROOT,
              "| %d | %s | %d | %d | %.1f | %.1f | %.1f |%n",
              run.rate(),
              run.target(),
              run.sent(),
              run.errors(),
              run.p50(),
              run.p95(),
              run.p99()));
    }
    md.append("\n| Rate (req/s) | Added p50 (ms) | Added p95 (ms) | Added p99 (ms) |\n");
    md.append("|---|---|---|---|\n");
    for (int index = 0; index + 1 < runs.size(); index += 2) {
      Run direct = runs.get(index);
      Run aduana = runs.get(index + 1);
      md.append(
          String.format(
              Locale.ROOT,
              "| %d | %.1f | %.1f | %.1f |%n",
              direct.rate(),
              aduana.p50() - direct.p50(),
              aduana.p95() - direct.p95(),
              aduana.p99() - direct.p99()));
    }
    Files.writeString(Path.of("bench/REPORT.md"), md, StandardCharsets.UTF_8);
  }

  /**
   * Describes the machine from inside the container.
   *
   * @return CPU model, cores and memory as the JVM sees them
   * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
   */
  private static String machine() {
    String cpu = "unknown CPU";
    try {
      for (String line : Files.readAllLines(Path.of("/proc/cpuinfo"))) {
        if (line.startsWith("model name")) {
          cpu = line.substring(line.indexOf(':') + 1).trim();
          break;
        }
      }
    } catch (IOException exception) {
      cpu = "unknown CPU";
    }
    long memory = 0;
    try {
      for (String line : Files.readAllLines(Path.of("/proc/meminfo"))) {
        if (line.startsWith("MemTotal:")) {
          memory = Long.parseLong(line.replaceAll("\\D", "")) / 1024 / 1024;
        }
      }
    } catch (IOException | NumberFormatException exception) {
      memory = 0;
    }
    return cpu
        + ", "
        + Runtime.getRuntime().availableProcessors()
        + " logical CPUs visible to Docker, "
        + memory
        + " GiB memory visible to Docker";
  }
}
