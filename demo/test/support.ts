// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Ignacio Rodríguez Caro
import type { Server } from 'node:http';
import { createCrmServer, seedCustomers } from '../servers/crm/server.js';
import { createPaymentsServer, seedPayments } from '../servers/payments/server.js';
import { mcpUrl, serveHttp } from '../servers/shared/http.js';
import { createWebfetchServer, demoPages } from '../servers/webfetch/server.js';

/** The three demo servers running in this process on free ports. */
export interface RunningServers {
  readonly urls: Record<string, string>;
  close(): Promise<void>;
}

const LOCAL = { port: 0, host: '127.0.0.1', allowedHostnames: ['127.0.0.1', 'localhost'] } as const;

/**
 * Starts fresh crm, payments and webfetch servers on ephemeral ports.
 *
 * @returns their MCP URLs by server name and a function that stops them
 * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
 */
export async function startDemoServers(): Promise<RunningServers> {
  const customers = seedCustomers();
  const payments = seedPayments();
  const pages = demoPages();
  const servers: Record<string, Server> = {
    crm: await serveHttp(() => createCrmServer(customers), LOCAL),
    payments: await serveHttp(() => createPaymentsServer(payments), LOCAL),
    webfetch: await serveHttp(() => createWebfetchServer(pages), LOCAL),
  };
  const urls = Object.fromEntries(Object.entries(servers).map(([name, server]) => [name, mcpUrl(server)]));
  return {
    urls,
    close: async () => {
      await Promise.all(
        Object.values(servers).map((server) => new Promise<void>((resolve) => server.close(() => resolve()))),
      );
    },
  };
}

/** A JSON-RPC response as the contract tests read it. */
export interface RpcResponse {
  readonly status: number;
  readonly body: { result?: Record<string, unknown>; error?: { code: number; message: string } };
}

/**
 * Sends one JSON-RPC request the way a 2026-07-28 client must: protocol headers and `_meta` on every call.
 *
 * @param url - the MCP endpoint
 * @param method - the JSON-RPC method
 * @param params - method parameters; the required `_meta` keys are added
 * @returns the HTTP status and the parsed JSON-RPC response, from a JSON body or an SSE stream
 * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
 */
export async function rpc(url: string, method: string, params: Record<string, unknown> = {}): Promise<RpcResponse> {
  const headers: Record<string, string> = {
    'content-type': 'application/json',
    accept: 'application/json, text/event-stream',
    'mcp-protocol-version': '2026-07-28',
    'mcp-method': method,
  };
  if (method === 'tools/call' && typeof params['name'] === 'string') {
    headers['mcp-name'] = params['name'];
  }
  const meta = {
    'io.modelcontextprotocol/protocolVersion': '2026-07-28',
    'io.modelcontextprotocol/clientCapabilities': {},
    'io.modelcontextprotocol/clientInfo': { name: 'contract-test', version: '0.1.0' },
  };
  const response = await fetch(url, {
    method: 'POST',
    headers,
    body: JSON.stringify({ jsonrpc: '2.0', id: 1, method, params: { ...params, _meta: meta } }),
  });
  const text = await response.text();
  const json = response.headers.get('content-type')?.includes('text/event-stream') ? lastSseData(text) : text;
  return { status: response.status, body: JSON.parse(json) as RpcResponse['body'] };
}

/**
 * Returns the data of the last SSE event, which carries the JSON-RPC response.
 *
 * @param stream - the raw `text/event-stream` body
 * @returns the JSON text of the final event
 * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
 */
function lastSseData(stream: string): string {
  const events = stream
    .split(/\r?\n\r?\n/)
    .map((event) =>
      event
        .split(/\r?\n/)
        .filter((line) => line.startsWith('data:'))
        .map((line) => line.slice(5).trimStart())
        .join('\n'),
    )
    .filter((data) => data.length > 0);
  return events.at(-1) ?? '{}';
}
