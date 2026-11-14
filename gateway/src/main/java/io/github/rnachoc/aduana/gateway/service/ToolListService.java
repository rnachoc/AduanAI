// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Ignacio Rodríguez Caro
package io.github.rnachoc.aduana.gateway.service;

import io.github.rnachoc.aduana.gateway.catalog.CatalogService;
import io.github.rnachoc.aduana.gateway.catalog.CatalogTool;
import io.github.rnachoc.aduana.gateway.identity.Principal;
import io.github.rnachoc.aduana.gateway.policy.PolicyService;
import org.springframework.stereotype.Service;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Builds the agent's tool list from the catalog's pinned definitions, never from what a server
 * publishes at the moment of the request.
 *
 * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
 */
@Service
public class ToolListService {

  private final CatalogService catalog;
  private final PolicyService policies;
  private final JsonMapper json;

  /**
   * Creates the service.
   *
   * @param catalog the catalog
   * @param policies hides tools the active policy can never let the caller use
   * @param json the shared JSON mapper
   * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
   */
  public ToolListService(CatalogService catalog, PolicyService policies, JsonMapper json) {
    this.catalog = catalog;
    this.policies = policies;
    this.json = json;
  }

  /**
   * Lists the tools offered to a principal.
   *
   * @param principal the caller
   * @return the `tools/list` result, sorted by agent-facing name
   * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
   */
  public ObjectNode listTools(Principal principal) {
    ObjectNode result = json.createObjectNode();
    result.put("resultType", "complete");
    ArrayNode tools = result.putArray("tools");
    catalog.offeredTools(principal.tenantId()).stream()
        .filter(tool -> policies.mayOffer(principal, tool))
        .sorted((a, b) -> qualified(a).compareTo(qualified(b)))
        .forEach(tool -> tools.add(agentView(tool)));
    result.put("ttlMs", 0);
    result.put("cacheScope", "private");
    return result;
  }

  /**
   * Returns the pinned definition under the agent-facing name.
   *
   * @param tool the catalog tool
   * @return the tool object for `tools/list`
   * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
   */
  private ObjectNode agentView(CatalogTool tool) {
    ObjectNode view = ((ObjectNode) tool.definition()).deepCopy();
    view.put("name", qualified(tool));
    return view;
  }

  /**
   * Returns the agent-facing name of a catalog tool.
   *
   * @param tool the tool
   * @return `<server>__<tool>`
   * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
   */
  private static String qualified(CatalogTool tool) {
    return new ToolName(tool.serverName(), tool.name()).qualified();
  }
}
