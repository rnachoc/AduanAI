// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Ignacio Rodríguez Caro
import assert from 'node:assert/strict';
import { after, before, describe, it } from 'node:test';
import { type RunningServers, rpc, startDemoServers } from './support.js';

const EXPECTED_TOOLS: Record<string, string[]> = {
  crm: ['get_customer', 'search_customers', 'update_customer_email'],
  payments: ['get_order', 'list_refunds', 'refund'],
  webfetch: ['fetch_url'],
};

const SAMPLE_CALLS: Record<string, { name: string; arguments: Record<string, unknown> }> = {
  crm: { name: 'get_customer', arguments: { customer_id: 'C-1002' } },
  payments: { name: 'get_order', arguments: { order_id: 'O-1003' } },
  webfetch: { name: 'fetch_url', arguments: { url: 'https://shop.example.com/returns' } },
};

describe('demo servers follow MCP 2026-07-28', () => {
  let servers: RunningServers;
  before(async () => {
    servers = await startDemoServers();
  });
  after(async () => {
    await servers.close();
  });

  for (const name of Object.keys(EXPECTED_TOOLS)) {
    it(`${name} answers server/discover with 2026-07-28 and the tools capability`, async () => {
      const response = await rpc(servers.urls[name] ?? '', 'server/discover');
      assert.equal(response.status, 200);
      const result = response.body.result ?? {};
      assert.ok((result['supportedVersions'] as string[]).includes('2026-07-28'));
      assert.ok((result['capabilities'] as Record<string, unknown>)['tools']);
    });

    it(`${name} lists its tools with input schemas and cache hints`, async () => {
      const response = await rpc(servers.urls[name] ?? '', 'tools/list');
      const result = response.body.result ?? {};
      const tools = result['tools'] as { name: string; inputSchema: { type: string } }[];
      assert.deepEqual(tools.map((tool) => tool.name).sort(), EXPECTED_TOOLS[name]);
      tools.forEach((tool) => assert.equal(tool.inputSchema.type, 'object'));
      assert.equal(typeof result['ttlMs'], 'number');
      assert.equal(typeof result['cacheScope'], 'string');
    });

    it(`${name} returns structured content mirrored in a text block`, async () => {
      const response = await rpc(servers.urls[name] ?? '', 'tools/call', SAMPLE_CALLS[name]);
      const result = response.body.result ?? {};
      assert.notEqual(result['isError'], true);
      const content = result['content'] as { type: string; text: string }[];
      assert.deepEqual(JSON.parse(content[0]?.text ?? 'null'), result['structuredContent']);
    });
  }

  it('an unknown tool is a protocol error, not a tool result', async () => {
    const response = await rpc(servers.urls['crm'] ?? '', 'tools/call', { name: 'drop_tables', arguments: {} });
    assert.equal(response.body.result, undefined);
    assert.equal(response.body.error?.code, -32602);
  });

  it('arguments outside the input schema are refused', async () => {
    const response = await rpc(servers.urls['payments'] ?? '', 'tools/call', {
      name: 'refund',
      arguments: { order_id: 'O-1001', amount_cents: -5, destination_iban: 'XX7900000000000000000101' },
    });
    const isError = response.body.result?.['isError'] === true || response.body.error !== undefined;
    assert.ok(isError);
  });
});
