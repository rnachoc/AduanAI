// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Ignacio Rodríguez Caro
import { McpServer } from '@modelcontextprotocol/server';
import * as z from 'zod';
import { errorResult, jsonResult, listenOptionsFromEnvironment, serveHttp } from '../shared/http.js';

/** One synthetic customer. Every e-mail is under example.com and every phone is a placeholder. */
export interface Customer {
  readonly id: string;
  readonly name: string;
  email: string;
  readonly phone: string;
  readonly segment: 'retail' | 'business';
}

/**
 * Returns the fixed synthetic customers the CRM starts with.
 *
 * @returns fresh copies, so each store can mutate its own
 * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
 */
export function seedCustomers(): Customer[] {
  return [
    { id: 'C-1001', name: 'Ana Ejemplo', email: 'ana@example.com', phone: '+34 600 000 001', segment: 'retail' },
    { id: 'C-1002', name: 'Bruno Prueba', email: 'bruno@example.com', phone: '+34 600 000 002', segment: 'retail' },
    {
      id: 'C-1003',
      name: 'Carla Ficticia SL',
      email: 'compras@carla.example.com',
      phone: '+34 900 000 003',
      segment: 'business',
    },
    { id: 'C-1004', name: 'Diego Inventado', email: 'diego@example.com', phone: '+34 600 000 004', segment: 'retail' },
  ];
}

/**
 * Builds the CRM demo server over a customer store that outlives each request.
 *
 * @param customers - the mutable store shared by every request
 * @returns an MCP server with `search_customers`, `get_customer` and `update_customer_email`
 * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
 */
export function createCrmServer(customers: Customer[]): McpServer {
  const server = new McpServer({ name: 'aduana-demo-crm', version: '0.1.0' });
  server.registerTool(
    'search_customers',
    {
      description: 'Search customers by a case-insensitive fragment of their name.',
      inputSchema: z.object({ query: z.string().min(1).max(100) }).strict(),
      annotations: { readOnlyHint: true },
    },
    ({ query }) => {
      const needle = query.toLowerCase();
      const matches = customers
        .filter((customer) => customer.name.toLowerCase().includes(needle))
        .map(({ id, name, segment }) => ({ id, name, segment }));
      return jsonResult({ customers: matches });
    },
  );
  server.registerTool(
    'get_customer',
    {
      description: 'Get one customer, including contact details, by id.',
      inputSchema: z.object({ customer_id: z.string().regex(/^C-\d{4}$/) }).strict(),
      annotations: { readOnlyHint: true },
    },
    ({ customer_id }) => {
      const customer = customers.find((candidate) => candidate.id === customer_id);
      return customer ? jsonResult({ customer: { ...customer } }) : errorResult(`No customer ${customer_id}.`);
    },
  );
  server.registerTool(
    'update_customer_email',
    {
      description: "Change a customer's e-mail address.",
      inputSchema: z.object({ customer_id: z.string().regex(/^C-\d{4}$/), email: z.email() }).strict(),
      annotations: { readOnlyHint: false, destructiveHint: true },
    },
    ({ customer_id, email }) => {
      const customer = customers.find((candidate) => candidate.id === customer_id);
      if (!customer) {
        return errorResult(`No customer ${customer_id}.`);
      }
      const previous = customer.email;
      customer.email = email;
      return jsonResult({ customer_id, previous_email: previous, email });
    },
  );
  return server;
}

/**
 * Starts the CRM server when this file is run directly.
 *
 * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
 */
export async function main(): Promise<void> {
  const customers = seedCustomers();
  const options = listenOptionsFromEnvironment(3001);
  await serveHttp(() => createCrmServer(customers), options);
  console.log(`crm listening on ${options.host}:${options.port}`);
}

if (import.meta.url === `file://${process.argv[1]}`) {
  await main();
}
