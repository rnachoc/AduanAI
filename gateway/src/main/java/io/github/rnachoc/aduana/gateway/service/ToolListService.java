// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Ignacio Rodríguez Caro
package io.github.rnachoc.aduana.gateway.service;

import io.github.rnachoc.aduana.gateway.config.AduanaProperties;
import io.github.rnachoc.aduana.gateway.upstream.UpstreamClient;
import io.github.rnachoc.aduana.gateway.upstream.UpstreamResponse;
import io.github.rnachoc.aduana.gateway.upstream.UpstreamServer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Builds the agent's tool list from every upstream, renaming each tool to `<server>__<tool>`.
 *
 * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
 */
@Service
public class ToolListService {

  private static final Logger LOG = LoggerFactory.getLogger(ToolListService.class);
  private static final int MAX_PAGES = 50;

  private final AduanaProperties properties;
  private final UpstreamClient upstreams;
  private final JsonMapper json;

  /**
   * Creates the service.
   *
   * @param properties the configured upstreams
   * @param upstreams the upstream client
   * @param json the shared JSON mapper
   * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
   */
  public ToolListService(AduanaProperties properties, UpstreamClient upstreams, JsonMapper json) {
    this.properties = properties;
    this.upstreams = upstreams;
    this.json = json;
  }

  /**
   * Lists the tools of every upstream that answers; an upstream that does not is left out.
   *
   * @return the `tools/list` result, in deterministic order as the specification asks
   * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
   */
  public ObjectNode listTools() {
    List<ObjectNode> tools = new ArrayList<>();
    for (UpstreamServer server : properties.upstreams()) {
      tools.addAll(toolsOf(server));
    }
    tools.sort(Comparator.comparing(tool -> tool.path("name").asString()));
    ObjectNode result = json.createObjectNode();
    result.put("resultType", "complete");
    result.putArray("tools").addAll(tools);
    result.put("ttlMs", 0);
    result.put("cacheScope", "private");
    return result;
  }

  /**
   * Reads every page of one upstream's tool list and renames the tools.
   *
   * @param server the upstream
   * @return its tools under agent-facing names; empty when it does not answer
   * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
   */
  private List<ObjectNode> toolsOf(UpstreamServer server) {
    List<ObjectNode> tools = new ArrayList<>();
    ObjectNode params = json.createObjectNode();
    for (int page = 0; page < MAX_PAGES; page++) {
      UpstreamResponse response = upstreams.send(server, "tools/list", params);
      if (!(response instanceof UpstreamResponse.Result(JsonNode result))) {
        LOG.warn("upstream {} did not list its tools: {}", server.name(), response);
        return List.of();
      }
      for (JsonNode tool : result.path("tools")) {
        ObjectNode renamed = ((ObjectNode) tool).deepCopy();
        renamed.put("name", new ToolName(server.name(), tool.path("name").asString()).qualified());
        tools.add(renamed);
      }
      if (!result.path("nextCursor").isString()) {
        break;
      }
      params = json.createObjectNode().put("cursor", result.get("nextCursor").asString());
    }
    return tools;
  }
}
