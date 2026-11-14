// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Ignacio Rodríguez Caro
import assert from 'node:assert/strict';
import { readdir, readFile } from 'node:fs/promises';
import { join } from 'node:path';
import { afterEach, beforeEach, describe, it } from 'node:test';
import { check, parseArguments, runScenario } from '../agent/agent.js';
import { loadScenario, parseScenario } from '../agent/scenario.js';
import { seedCustomers } from '../servers/crm/server.js';
import { seedPayments } from '../servers/payments/server.js';
import { demoPages } from '../servers/webfetch/server.js';
import { type RunningServers, startDemoServers } from './support.js';

const SCENARIOS = join(import.meta.dirname, '..', '..', 'scenarios');

describe('the scripted agent', () => {
  let servers: RunningServers;
  beforeEach(async () => {
    servers = await startDemoServers();
  });
  afterEach(async () => {
    await servers.close();
  });

  it('speaks the legacy 2025-11-25 handshake when asked to', async () => {
    const scenario = await loadScenario(join(SCENARIOS, 'support-happy-path.yaml'));
    const outcomes = await runScenario(scenario, { kind: 'direct', urls: servers.urls, legacy: true });
    assert.deepEqual(
      outcomes.filter((outcome) => !outcome.passed),
      [],
    );
  });

  for (const file of ['support-happy-path.yaml', 'injected-refund-direct.yaml']) {
    it(`runs ${file} straight against the servers and every step passes`, async () => {
      const scenario = await loadScenario(join(SCENARIOS, file));
      const outcomes = await runScenario(scenario, { kind: 'direct', urls: servers.urls });
      assert.deepEqual(
        outcomes.filter((outcome) => !outcome.passed),
        [],
      );
      assert.equal(outcomes.length, scenario.steps.length);
    });
  }

  it('reports a step whose result breaks its expectation', async () => {
    const scenario = parseScenario(
      'name: x\ndescription: y\nsteps:\n  - {server: crm, tool: get_customer, arguments: {customer_id: C-1001}, expect: {text_contains: [nobody]}}\n',
    );
    const [outcome] = await runScenario(scenario, { kind: 'direct', urls: servers.urls });
    assert.equal(outcome?.passed, false);
  });
});

describe('expectations', () => {
  it('an error code expectation reads the structured code', () => {
    const result = { isError: true, structuredContent: { code: 'POLICY_DENIED' }, content: [] };
    assert.deepEqual(check({ error_code: 'POLICY_DENIED' }, result, ''), []);
    assert.equal(check({ error_code: 'RATE_LIMITED' }, result, '').length, 1);
  });

  it('the command line selects direct or gateway mode', () => {
    const direct = parseArguments(['--direct', 'crm=http://a/mcp,payments=http://b/mcp', 'x.yaml']);
    assert.deepEqual(direct.target, {
      kind: 'direct',
      urls: { crm: 'http://a/mcp', payments: 'http://b/mcp' },
      legacy: false,
    });
    const legacy = parseArguments(['--legacy', '--direct', 'crm=http://a/mcp', 'x.yaml']);
    assert.equal(legacy.target.legacy, true);
    assert.deepEqual(legacy.files, ['x.yaml']);
    const gateway = parseArguments(['--gateway', 'http://g/mcp', 'y.yaml']);
    assert.equal(gateway.target.kind, 'gateway');
    assert.throws(() => parseArguments(['x.yaml']));
  });
});

describe('demo data is visibly synthetic', () => {
  it('every e-mail address is under example.com', async () => {
    const text = JSON.stringify([seedCustomers(), [...demoPages().values()], await allScenarioText()]);
    const emails = text.match(/[\w.+-]+@[\w.-]+\.[a-z]{2,}/gi) ?? [];
    assert.ok(emails.length > 0);
    emails.forEach((email) => assert.match(email, /@([\w-]+\.)*example\.com$/i));
  });

  it('every IBAN uses the non-existent country code XX', async () => {
    const text = JSON.stringify([seedPayments(), [...demoPages().values()], await allScenarioText()]);
    const ibans = text.match(/\b[A-Z]{2}\d{2}[A-Z0-9]{16,30}\b/g) ?? [];
    assert.ok(ibans.length > 0);
    ibans.forEach((iban) => assert.match(iban, /^XX/));
  });

  it('every phone number is a 000 placeholder', () => {
    seedCustomers().forEach((customer) => assert.match(customer.phone, /^\+34 [69]00 000 \d{3}$/));
  });
});

/**
 * Concatenates every scenario file, so data checks also cover what the agent sends.
 *
 * @returns the text of all scenarios
 * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
 */
async function allScenarioText(): Promise<string> {
  const files = (await readdir(SCENARIOS)).filter((file) => file.endsWith('.yaml'));
  const texts = await Promise.all(files.map((file) => readFile(join(SCENARIOS, file), 'utf8')));
  return texts.join('\n');
}
