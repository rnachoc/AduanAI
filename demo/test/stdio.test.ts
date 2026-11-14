// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Ignacio Rodríguez Caro
import assert from 'node:assert/strict';
import { fileURLToPath } from 'node:url';
import { after, before, describe, it } from 'node:test';
import { Client } from '@modelcontextprotocol/client';
import { StdioClientTransport } from '@modelcontextprotocol/client/stdio';

const SERVER = fileURLToPath(new URL('../servers/notes/server.js', import.meta.url));

describe('the notes server speaks MCP 2026-07-28 over stdio', () => {
  let client: Client;

  before(async () => {
    client = new Client(
      { name: 'aduana-stdio-contract', version: '0.1.0' },
      { versionNegotiation: { mode: { pin: '2026-07-28' } } },
    );
    await client.connect(new StdioClientTransport({ command: process.execPath, args: [SERVER] }));
  });

  after(async () => {
    await client.close();
  });

  it('lists its three tools', async () => {
    const { tools } = await client.listTools();
    assert.deepEqual(tools.map((tool) => tool.name).sort(), ['add_note', 'list_notes', 'read_note']);
  });

  it('adds a note and reads it back', async () => {
    const added = (await client.callTool({
      name: 'add_note',
      arguments: { title: 'Test', body: 'Written over stdio.' },
    })) as { structuredContent: { id: string } };
    const read = (await client.callTool({ name: 'read_note', arguments: { id: added.structuredContent.id } })) as {
      structuredContent: { body: string };
    };
    assert.equal(read.structuredContent.body, 'Written over stdio.');
  });

  it('answers an unknown note as a tool error', async () => {
    const result = await client.callTool({ name: 'read_note', arguments: { id: 'N-999' } });
    assert.equal(result.isError, true);
  });
});
