// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Ignacio Rodríguez Caro
import { createServer, type Server } from 'node:http';
import type { AddressInfo } from 'node:net';
import { hostHeaderValidation, toNodeHandler } from '@modelcontextprotocol/node';
import { createMcpHandler, type McpServer } from '@modelcontextprotocol/server';

/** Where a demo server listens and which Host headers it accepts. */
export interface ListenOptions {
  readonly port: number;
  readonly host: string;
  readonly allowedHostnames: readonly string[];
}

/**
 * Reads the listen options from the environment, so the same server runs on a laptop and in compose.
 *
 * @param defaultPort - port used when `PORT` is not set
 * @returns the port, the bind address and the Host names the server accepts
 * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
 */
export function listenOptionsFromEnvironment(defaultPort: number): ListenOptions {
  const allowed = process.env['ALLOWED_HOSTS'] ?? 'localhost,127.0.0.1';
  return {
    port: Number(process.env['PORT'] ?? defaultPort),
    host: process.env['BIND'] ?? '127.0.0.1',
    allowedHostnames: allowed.split(',').map((name) => name.trim()),
  };
}

/**
 * Serves an MCP server factory over Streamable HTTP at `/mcp`, one fresh server per request.
 *
 * The 2026-07-28 core is stateless, so a factory per request keeps nothing between calls except the
 * data store the caller closes over.
 *
 * @param factory - builds the MCP server that answers one request
 * @param options - listen address and accepted Host names
 * @returns the started HTTP server
 * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
 */
export async function serveHttp(factory: () => McpServer, options: ListenOptions): Promise<Server> {
  const handler = toNodeHandler(createMcpHandler(() => factory()));
  const validateHost = hostHeaderValidation([...options.allowedHostnames]);
  const server = createServer((request, response) => {
    if (request.url === '/healthz') {
      response.writeHead(200, { 'content-type': 'application/json' }).end('{"status":"ok"}');
      return;
    }
    if (!request.url?.startsWith('/mcp')) {
      response.writeHead(404).end();
      return;
    }
    if (!validateHost(request, response)) {
      return;
    }
    handler(request, response).catch((error: unknown) => {
      response.writeHead(500).end(String(error));
    });
  });
  await new Promise<void>((resolve) => server.listen(options.port, options.host, resolve));
  return server;
}

/**
 * Returns the URL of the MCP endpoint of a started server, for tests that bind port 0.
 *
 * @param server - a listening HTTP server
 * @returns the `/mcp` URL on 127.0.0.1 and the server's real port
 * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
 */
export function mcpUrl(server: Server): string {
  const address = server.address() as AddressInfo;
  return `http://127.0.0.1:${address.port}/mcp`;
}

/**
 * Builds a tool result that carries the same value as JSON text and as structured content.
 *
 * The specification asks tools that return structured content to also return it serialised in a
 * text block, for clients that read only text.
 *
 * @param value - the result object
 * @returns a `tools/call` result
 * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
 */
export function jsonResult(value: Record<string, unknown>): {
  content: { type: 'text'; text: string }[];
  structuredContent: Record<string, unknown>;
} {
  return { content: [{ type: 'text', text: JSON.stringify(value) }], structuredContent: value };
}

/**
 * Builds a tool execution error, which the specification returns as a result with `isError`.
 *
 * @param message - what went wrong, for the agent
 * @returns a `tools/call` result flagged as an error
 * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
 */
export function errorResult(message: string): {
  content: { type: 'text'; text: string }[];
  isError: true;
} {
  return { content: [{ type: 'text', text: message }], isError: true };
}
