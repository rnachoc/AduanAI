// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Ignacio Rodríguez Caro
package io.github.rnachoc.aduana.gateway.upstream;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Runs local MCP servers as child processes and talks to them over stdin and stdout, one JSON-RPC
 * message per line (plan Phase 8).
 *
 * <p>Only commands on the configured allow-list are launched (`aduana.stdio.allowed-commands`,
 * absolute paths). A child gets an empty environment except `PATH` and `LANG`, its stderr is
 * discarded, and it is started on the first call. If it exits or stops answering in time, it is
 * killed and every pending call fails at once; the next call starts it again, unless it failed
 * recently, in which case the call fails until a backoff of 0.5 s doubling to 30 s has passed.
 *
 * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
 */
@Component
public class StdioConnector implements AutoCloseable {

  private static final Logger LOG = LoggerFactory.getLogger(StdioConnector.class);
  private static final int MAX_LINE_BYTES = 8 * 1024 * 1024;
  private static final Duration FIRST_BACKOFF = Duration.ofMillis(500);
  private static final Duration MAX_BACKOFF = Duration.ofSeconds(30);

  private final JsonMapper json;
  private final Clock clock;
  private final Set<String> allowed;
  private final Map<String, Child> children = new ConcurrentHashMap<>();
  private final Map<String, Backoff> backoffs = new ConcurrentHashMap<>();

  /**
   * When a server that failed may be started again.
   *
   * @param failures consecutive failures
   * @param notBefore the earliest next start
   * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
   */
  private record Backoff(int failures, Instant notBefore) {}

  /**
   * Creates the connector.
   *
   * @param json the shared JSON mapper
   * @param clock the clock
   * @param allowed absolute paths of the commands that may be launched
   * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
   */
  public StdioConnector(
      JsonMapper json,
      Clock clock,
      @Value("${aduana.stdio.allowed-commands:}") List<String> allowed) {
    this.json = json;
    this.clock = clock;
    this.allowed =
        allowed.stream()
            .filter(command -> !command.isBlank())
            .map(command -> Path.of(command).normalize().toString())
            .collect(Collectors.toUnmodifiableSet());
  }

  /**
   * Says whether a command line may be launched.
   *
   * @param command the command and its arguments
   * @return true when the command's path is on the allow-list
   * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
   */
  public boolean allowed(List<String> command) {
    return !command.isEmpty() && allowed.contains(Path.of(command.get(0)).normalize().toString());
  }

  /**
   * Sends one JSON-RPC request to a stdio server and waits at most its timeout.
   *
   * @param server the upstream
   * @param body the complete JSON-RPC request
   * @param id the request's id
   * @return the server's answer, or why there is none
   * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
   */
  public UpstreamResponse send(UpstreamServer server, ObjectNode body, long id) {
    List<String> command = server.command();
    if (!allowed(command)) {
      LOG.warn(
          "stdio server {} not started: {} is not on the allow-list",
          server.name(),
          command.get(0));
      return new UpstreamResponse.Unavailable("command not allowed");
    }
    Optional<Child> child;
    try {
      child = running(server, command);
    } catch (IOException exception) {
      failed(server.name());
      LOG.warn(
          "stdio server {} could not start: {}",
          server.name(),
          exception.getClass().getSimpleName());
      return new UpstreamResponse.Unavailable("could not start");
    }
    if (child.isEmpty()) {
      return new UpstreamResponse.Unavailable("restarting after a failure");
    }
    CompletableFuture<JsonNode> answer = child.get().request(id, json.writeValueAsBytes(body));
    try {
      JsonNode message = answer.get(server.timeout().toMillis(), TimeUnit.MILLISECONDS);
      backoffs.remove(server.name());
      return interpret(message);
    } catch (TimeoutException exception) {
      LOG.warn(
          "stdio server {} did not answer in {}, stopping it", server.name(), server.timeout());
      stop(server.name(), child.get());
      failed(server.name());
      return new UpstreamResponse.TimedOut();
    } catch (ExecutionException exception) {
      LOG.warn("stdio server {} failed: {}", server.name(), String.valueOf(exception.getCause()));
      stop(server.name(), child.get());
      failed(server.name());
      return new UpstreamResponse.Unavailable("process exited");
    } catch (InterruptedException exception) {
      Thread.currentThread().interrupt();
      return new UpstreamResponse.Unavailable("interrupted");
    } finally {
      child.get().forget(id);
    }
  }

  /**
   * Returns the server's running process, starting it when there is none and no backoff applies.
   *
   * @param server the upstream
   * @param command the allowed command line
   * @return the process, or empty while a backoff applies
   * @throws IOException if the process cannot be started
   * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
   */
  private synchronized Optional<Child> running(UpstreamServer server, List<String> command)
      throws IOException {
    Child current = children.get(server.name());
    if (current != null && current.alive() && current.command().equals(command)) {
      return Optional.of(current);
    }
    if (current != null) {
      stop(server.name(), current);
    }
    Backoff backoff = backoffs.get(server.name());
    if (backoff != null && clock.instant().isBefore(backoff.notBefore())) {
      return Optional.empty();
    }
    ProcessBuilder builder = new ProcessBuilder(command);
    builder.environment().clear();
    builder.environment().put("PATH", "/usr/local/bin:/usr/bin:/bin");
    builder.environment().put("LANG", "C.UTF-8");
    builder.redirectError(ProcessBuilder.Redirect.DISCARD);
    Child started = new Child(command, builder.start(), json, server.name());
    children.put(server.name(), started);
    LOG.info("stdio server {} started, pid {}", server.name(), started.process().pid());
    return Optional.of(started);
  }

  /**
   * Records a failure and pushes the next start back.
   *
   * @param name the server
   * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
   */
  private void failed(String name) {
    backoffs.compute(
        name,
        (key, previous) -> {
          int failures = previous == null ? 1 : previous.failures() + 1;
          long millis = FIRST_BACKOFF.toMillis() << Math.min(failures - 1, 10);
          Duration wait = Duration.ofMillis(Math.min(millis, MAX_BACKOFF.toMillis()));
          return new Backoff(failures, clock.instant().plus(wait));
        });
  }

  /**
   * Kills a child and forgets it.
   *
   * @param name the server
   * @param child the process
   * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
   */
  private void stop(String name, Child child) {
    children.remove(name, child);
    child.kill();
  }

  /**
   * Maps a JSON-RPC response to an upstream answer.
   *
   * @param message the response
   * @return the answer
   * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
   */
  private static UpstreamResponse interpret(JsonNode message) {
    if (message.has("error")) {
      JsonNode error = message.get("error");
      return new UpstreamResponse.RpcError(
          error.path("code").asInt(), error.path("message").asString());
    }
    if (!message.path("result").isObject()) {
      return new UpstreamResponse.Unavailable("no result");
    }
    return new UpstreamResponse.Result(message.get("result"));
  }

  /**
   * Kills every child, when the gateway stops.
   *
   * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
   */
  @Override
  public void close() {
    children.forEach((name, child) -> child.kill());
    children.clear();
  }

  /**
   * A running child process and the calls waiting for its answers.
   *
   * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
   */
  private static final class Child {

    private final List<String> command;
    private final Process process;
    private final JsonMapper json;
    private final OutputStream stdin;
    private final Map<Long, CompletableFuture<JsonNode>> pending = new ConcurrentHashMap<>();

    /**
     * Wraps a started process and starts reading its output.
     *
     * @param command the command line
     * @param process the process
     * @param json the JSON mapper
     * @param name the server name, for the reader thread
     * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
     */
    Child(List<String> command, Process process, JsonMapper json, String name) {
      this.command = command;
      this.process = process;
      this.json = json;
      this.stdin = process.getOutputStream();
      Thread.ofVirtual().name("stdio-" + name).start(this::read);
    }

    /**
     * The command line this process runs.
     *
     * @return the command
     * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
     */
    List<String> command() {
      return command;
    }

    /**
     * The process.
     *
     * @return the process
     * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
     */
    Process process() {
      return process;
    }

    /**
     * Says whether the process is still running.
     *
     * @return true while it runs
     * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
     */
    boolean alive() {
      return process.isAlive();
    }

    /**
     * Writes a request line and returns the future of its answer.
     *
     * @param id the request id
     * @param line the serialised request, without the newline
     * @return the future answer; failed if the process cannot be written to
     * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
     */
    CompletableFuture<JsonNode> request(long id, byte[] line) {
      CompletableFuture<JsonNode> answer = new CompletableFuture<>();
      pending.put(id, answer);
      try {
        synchronized (stdin) {
          stdin.write(line);
          stdin.write('\n');
          stdin.flush();
        }
      } catch (IOException exception) {
        answer.completeExceptionally(exception);
      }
      if (!process.isAlive()) {
        answer.completeExceptionally(new IOException("process exited"));
      }
      return answer;
    }

    /**
     * Stops waiting for an id.
     *
     * @param id the request id
     * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
     */
    void forget(long id) {
      pending.remove(id);
    }

    /**
     * Kills the process and fails every pending call.
     *
     * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
     */
    void kill() {
      process.destroyForcibly();
      failPending();
    }

    /**
     * Fails every pending call.
     *
     * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
     */
    private void failPending() {
      pending.values().forEach(future -> future.completeExceptionally(new IOException("exited")));
    }

    /**
     * Reads answers line by line until the process closes its output; a line over 8 MiB kills it.
     *
     * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
     */
    private void read() {
      try (InputStream stdout = process.getInputStream()) {
        ByteArrayOutputStream line = new ByteArrayOutputStream();
        int next = stdout.read();
        for (; next != -1; next = stdout.read()) {
          if (next != '\n') {
            line.write(next);
            if (line.size() > MAX_LINE_BYTES) {
              LOG.warn("stdio server sent a line over {} bytes, stopping it", MAX_LINE_BYTES);
              break;
            }
            continue;
          }
          deliver(line.toString(StandardCharsets.UTF_8));
          line.reset();
        }
      } catch (IOException exception) {
        LOG.debug("stdio output closed: {}", exception.getClass().getSimpleName());
      }
      kill();
    }

    /**
     * Completes the call a response line answers; other lines are ignored.
     *
     * @param text the line
     * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
     */
    private void deliver(String text) {
      JsonNode message;
      try {
        message = json.readTree(text);
      } catch (JacksonException exception) {
        return;
      }
      if (message.path("id").isIntegralNumber() && !message.has("method")) {
        CompletableFuture<JsonNode> answer = pending.remove(message.get("id").asLong());
        if (answer != null) {
          answer.complete(message);
        }
      }
    }
  }
}
