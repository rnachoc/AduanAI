// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Ignacio Rodríguez Caro
package io.github.rnachoc.aduana.gateway.mcp;

/**
 * Names and numbers fixed by the MCP specification, revisions 2026-07-28 and 2025-11-25.
 *
 * <p>Sources, read on 2026-11-14, are listed in
 * docs/adr/0001-verified-facts-and-pinned-versions.md.
 *
 * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
 */
public final class McpProtocol {

  /** The revision Aduana implements. */
  public static final String MODERN_VERSION = "2026-07-28";

  /** The older revision Aduana still answers through the `initialize` handshake (ADR 4). */
  public static final String LEGACY_VERSION = "2025-11-25";

  /** The `_meta` key that carries the protocol revision of a modern request. */
  public static final String META_PROTOCOL_VERSION = "io.modelcontextprotocol/protocolVersion";

  /** The `_meta` key that carries the client capabilities of a modern request. */
  public static final String META_CLIENT_CAPABILITIES =
      "io.modelcontextprotocol/clientCapabilities";

  /** The `_meta` key that carries the client name and version. */
  public static final String META_CLIENT_INFO = "io.modelcontextprotocol/clientInfo";

  /** The result `_meta` key that carries the server name and version. */
  public static final String META_SERVER_INFO = "io.modelcontextprotocol/serverInfo";

  /** The HTTP header every request repeats the revision in. */
  public static final String HEADER_PROTOCOL_VERSION = "MCP-Protocol-Version";

  /** The HTTP header that repeats the JSON-RPC method. */
  public static final String HEADER_METHOD = "Mcp-Method";

  /** The HTTP header that repeats the tool name of a tools/call. */
  public static final String HEADER_NAME = "Mcp-Name";

  /** JSON-RPC: the body is not JSON. */
  public static final int PARSE_ERROR = -32700;

  /** JSON-RPC: the body is not a valid request object. */
  public static final int INVALID_REQUEST = -32600;

  /** JSON-RPC: the method does not exist or is not supported. */
  public static final int METHOD_NOT_FOUND = -32601;

  /** JSON-RPC: invalid parameters; MCP also uses it for an unknown tool. */
  public static final int INVALID_PARAMS = -32602;

  /** JSON-RPC: an error inside the server. */
  public static final int INTERNAL_ERROR = -32603;

  /** MCP 2026-07-28: an HTTP header contradicts the request body. */
  public static final int HEADER_MISMATCH = -32020;

  /** MCP 2026-07-28: the requested revision is not supported. */
  public static final int UNSUPPORTED_PROTOCOL_VERSION = -32022;

  /**
   * Prevents instances: the class only holds constants.
   *
   * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
   */
  private McpProtocol() {}
}
