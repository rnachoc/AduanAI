// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Ignacio Rodríguez Caro
package io.github.rnachoc.aduana.gateway.support;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A stdio MCP server for tests, run as a child process with only the JDK on its classpath.
 *
 * <p>Tools: `echo` answers its `text`; `env` answers the names of its environment variables;
 * `crash` exits without answering; `hang` never answers. Every start appends a line to the file
 * named by its first argument, so tests can count launches.
 *
 * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
 */
public final class FakeStdioServer {

  private static final Pattern ID = Pattern.compile("\"id\":(\\d+)");
  private static final Pattern METHOD = Pattern.compile("\"method\":\"([^\"]+)\"");
  private static final Pattern NAME = Pattern.compile("\"params\":\\{.*?\"name\":\"([^\"]+)\"");
  private static final Pattern TEXT = Pattern.compile("\"text\":\"([^\"]*)\"");
  private static final String SCHEMA =
      "{\"type\":\"object\",\"properties\":{\"text\":{\"type\":\"string\"}}}";

  /**
   * Not instantiated.
   *
   * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
   */
  private FakeStdioServer() {}

  /**
   * Serves until stdin closes.
   *
   * @param args the file to append a line to on every start
   * @throws IOException if stdin cannot be read
   * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
   */
  public static void main(String[] args) throws IOException {
    if (args.length > 0) {
      java.nio.file.Files.writeString(
          java.nio.file.Path.of(args[0]),
          "start\n",
          java.nio.file.StandardOpenOption.CREATE,
          java.nio.file.StandardOpenOption.APPEND);
    }
    PrintStream out = new PrintStream(System.out, true, StandardCharsets.UTF_8);
    BufferedReader in =
        new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
    for (String line = in.readLine(); line != null; line = in.readLine()) {
      Matcher id = ID.matcher(line);
      Matcher method = METHOD.matcher(line);
      if (!id.find() || !method.find()) {
        continue;
      }
      String result = answer(method.group(1), line);
      if (result == null) {
        continue;
      }
      out.println("{\"jsonrpc\":\"2.0\",\"id\":" + id.group(1) + "," + result + "}");
    }
  }

  /**
   * Builds the `result` or `error` member for a request.
   *
   * @param method the method
   * @param line the whole request
   * @return the member, or null to send nothing
   * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
   */
  private static String answer(String method, String line) {
    if (method.equals("tools/list")) {
      StringBuilder tools = new StringBuilder();
      for (String tool : new String[] {"echo", "env", "crash", "hang"}) {
        tools.append(tools.isEmpty() ? "" : ",");
        tools.append("{\"name\":\"").append(tool).append("\",\"description\":\"Test tool ");
        tools.append(tool).append(".\",\"inputSchema\":").append(SCHEMA).append('}');
      }
      return "\"result\":{\"tools\":[" + tools + "],\"ttlMs\":60000,\"cacheScope\":\"public\"}";
    }
    if (!method.equals("tools/call")) {
      return "\"error\":{\"code\":-32601,\"message\":\"Method not found\"}";
    }
    Matcher name = NAME.matcher(line);
    String tool = name.find() ? name.group(1) : "";
    switch (tool) {
      case "crash" -> {
        System.exit(3);
        return null;
      }
      case "hang" -> {
        return null;
      }
      case "env" -> {
        return text(String.join(",", new TreeSet<>(System.getenv().keySet())));
      }
      case "echo" -> {
        Matcher text = TEXT.matcher(line);
        return text(text.find() ? text.group(1) : "");
      }
      default -> {
        return "\"error\":{\"code\":-32602,\"message\":\"Unknown tool: " + tool + "\"}";
      }
    }
  }

  /**
   * Builds a text result.
   *
   * @param value the text, without characters that need escaping
   * @return the `result` member
   * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
   */
  private static String text(String value) {
    return "\"result\":{\"resultType\":\"complete\",\"content\":[{\"type\":\"text\",\"text\":\""
        + value
        + "\"}],\"isError\":false}";
  }
}
