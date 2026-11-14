// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Ignacio Rodríguez Caro
import { McpServer } from '@modelcontextprotocol/server';
import { serveStdio } from '@modelcontextprotocol/server/stdio';
import * as z from 'zod';
import { errorResult, jsonResult } from '../shared/http.js';

/** One note kept in memory for the life of the process. */
export interface Note {
  readonly id: string;
  readonly title: string;
  readonly body: string;
}

/**
 * Returns the notes a fresh notes server starts with.
 *
 * @returns a mutable map from note id to note
 * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
 */
export function seedNotes(): Map<string, Note> {
  return new Map([
    ['N-1', { id: 'N-1', title: 'Shift handover', body: 'Two refunds waiting for finance approval.' }],
    ['N-2', { id: 'N-2', title: 'Escalations', body: 'Customer C-1002 asked for a call back.' }],
  ]);
}

/**
 * Builds the notes demo server, the one Aduana runs as a child process over stdio.
 *
 * @param notes - the notes it reads and writes
 * @returns an MCP server with `list_notes`, `read_note` and `add_note`
 * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
 */
export function createNotesServer(notes: Map<string, Note>): McpServer {
  const server = new McpServer({ name: 'aduana-demo-notes', version: '0.1.0' });
  server.registerTool(
    'list_notes',
    {
      description: 'List the ids and titles of the team notes.',
      inputSchema: z.object({}).strict(),
      annotations: { readOnlyHint: true },
    },
    () => jsonResult({ notes: [...notes.values()].map(({ id, title }) => ({ id, title })) }),
  );
  server.registerTool(
    'read_note',
    {
      description: 'Read one team note.',
      inputSchema: z.object({ id: z.string().regex(/^N-\d{1,6}$/) }).strict(),
      annotations: { readOnlyHint: true },
    },
    ({ id }) => {
      const note = notes.get(id);
      return note === undefined ? errorResult(`No note ${id}`) : jsonResult({ ...note });
    },
  );
  server.registerTool(
    'add_note',
    {
      description: 'Add a team note.',
      inputSchema: z.object({ title: z.string().min(1).max(100), body: z.string().max(2000) }).strict(),
    },
    ({ title, body }) => {
      const id = `N-${notes.size + 1}`;
      notes.set(id, { id, title, body });
      return jsonResult({ id });
    },
  );
  return server;
}

/**
 * Serves the notes server on stdin and stdout when this file is run directly.
 *
 * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
 */
export function main(): void {
  const notes = seedNotes();
  serveStdio(() => createNotesServer(notes));
}

if (import.meta.url === `file://${process.argv[1]}`) {
  main();
}
