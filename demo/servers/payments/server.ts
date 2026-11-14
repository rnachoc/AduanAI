// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Ignacio Rodríguez Caro
import { McpServer } from '@modelcontextprotocol/server';
import * as z from 'zod';
import { errorResult, jsonResult, listenOptionsFromEnvironment, serveHttp } from '../shared/http.js';

/** One synthetic order. IBANs use the country code XX, which no country has (see demo/README.md). */
export interface Order {
  readonly id: string;
  readonly customerId: string;
  readonly totalCents: number;
  readonly currency: 'EUR';
  readonly paidFromIban: string;
}

/** One refund the payments server has executed. */
export interface Refund {
  readonly refundId: string;
  readonly orderId: string;
  readonly amountCents: number;
  readonly destinationIban: string;
  readonly reason: string;
}

/** The mutable state of the payments server, shared by every request. */
export interface PaymentsStore {
  readonly orders: readonly Order[];
  readonly refunds: Refund[];
}

/**
 * Returns the fixed synthetic orders and an empty refund list.
 *
 * @returns a fresh store
 * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
 */
export function seedPayments(): PaymentsStore {
  return {
    orders: [
      {
        id: 'O-1001',
        customerId: 'C-1001',
        totalCents: 4999,
        currency: 'EUR',
        paidFromIban: 'XX7900000000000000000101',
      },
      {
        id: 'O-1002',
        customerId: 'C-1002',
        totalCents: 129900,
        currency: 'EUR',
        paidFromIban: 'XX6800000000000000000202',
      },
      {
        id: 'O-1003',
        customerId: 'C-1003',
        totalCents: 25000,
        currency: 'EUR',
        paidFromIban: 'XX6800000000000000000202',
      },
    ],
    refunds: [],
  };
}

/**
 * Builds the payments demo server over a store that outlives each request.
 *
 * @param store - orders and executed refunds
 * @returns an MCP server with `get_order`, `refund` and `list_refunds`
 * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
 */
export function createPaymentsServer(store: PaymentsStore): McpServer {
  const server = new McpServer({ name: 'aduana-demo-payments', version: '0.1.0' });
  server.registerTool(
    'get_order',
    {
      description: 'Get an order by id.',
      inputSchema: z.object({ order_id: z.string().regex(/^O-\d{4}$/) }).strict(),
      annotations: { readOnlyHint: true },
    },
    ({ order_id }) => {
      const order = store.orders.find((candidate) => candidate.id === order_id);
      return order ? jsonResult({ order: { ...order } }) : errorResult(`No order ${order_id}.`);
    },
  );
  server.registerTool(
    'refund',
    {
      description: 'Refund part or all of an order to a bank account.',
      inputSchema: z
        .object({
          order_id: z.string().regex(/^O-\d{4}$/),
          amount_cents: z.number().int().positive(),
          destination_iban: z.string().regex(/^[A-Z]{2}\d{2}[A-Z0-9]{10,30}$/),
          reason: z.string().max(200).optional(),
        })
        .strict(),
      annotations: { readOnlyHint: false, destructiveHint: true },
    },
    ({ order_id, amount_cents, destination_iban, reason }) => {
      const order = store.orders.find((candidate) => candidate.id === order_id);
      if (!order) {
        return errorResult(`No order ${order_id}.`);
      }
      const alreadyRefunded = store.refunds
        .filter((refund) => refund.orderId === order_id)
        .reduce((sum, refund) => sum + refund.amountCents, 0);
      if (alreadyRefunded + amount_cents > order.totalCents) {
        return errorResult(`Refund exceeds the order total of ${order.totalCents} cents.`);
      }
      const refund: Refund = {
        refundId: `R-${String(store.refunds.length + 1).padStart(4, '0')}`,
        orderId: order_id,
        amountCents: amount_cents,
        destinationIban: destination_iban,
        reason: reason ?? '',
      };
      store.refunds.push(refund);
      return jsonResult({ refund: { ...refund } });
    },
  );
  server.registerTool(
    'list_refunds',
    {
      description: 'List executed refunds, optionally for one order.',
      inputSchema: z
        .object({
          order_id: z
            .string()
            .regex(/^O-\d{4}$/)
            .optional(),
        })
        .strict(),
      annotations: { readOnlyHint: true },
    },
    ({ order_id }) => {
      const refunds = store.refunds.filter((refund) => order_id === undefined || refund.orderId === order_id);
      return jsonResult({ refunds: refunds.map((refund) => ({ ...refund })) });
    },
  );
  return server;
}

/**
 * Starts the payments server when this file is run directly.
 *
 * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
 */
export async function main(): Promise<void> {
  const store = seedPayments();
  const options = listenOptionsFromEnvironment(3002);
  await serveHttp(() => createPaymentsServer(store), options);
  console.log(`payments listening on ${options.host}:${options.port}`);
}

if (import.meta.url === `file://${process.argv[1]}`) {
  await main();
}
