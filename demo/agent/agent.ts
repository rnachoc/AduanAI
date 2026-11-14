// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Ignacio Rodríguez Caro
import { Client, StreamableHTTPClientTransport } from '@modelcontextprotocol/client';
import { type Expectation, type Scenario, type Step, loadScenario } from './scenario.js';

/** Talk to each demo server on its own URL, with no gateway in between. */
export interface DirectTarget {
  readonly kind: 'direct';
  readonly urls: Readonly<Record<string, string>>;
  readonly legacy?: boolean;
}

/** Talk to every server through one Aduana endpoint, with tool names `<server>__<tool>`. */
export interface GatewayTarget {
  readonly kind: 'gateway';
  readonly url: string;
  readonly apiKey: string | undefined;
  readonly legacy?: boolean;
}

/** Where a scenario's calls go. */
export type Target = DirectTarget | GatewayTarget;

/** The outcome of one step: what came back and which expectations it broke. */
export interface StepOutcome {
  readonly step: string;
  readonly passed: boolean;
  readonly problems: readonly string[];
  readonly text: string;
}

/** The parts of a `tools/call` result the agent inspects. */
interface ToolResult {
  readonly isError?: boolean;
  readonly content?: readonly { type: string; text?: string }[];
  readonly structuredContent?: unknown;
}

/**
 * Runs the steps of a scenario in order and checks each result against its expectations.
 *
 * The agent is scripted: it never chooses a call, so a run is reproducible and needs no model.
 *
 * @param scenario - the steps to run
 * @param target - direct servers or the gateway
 * @returns one outcome per step
 * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
 */
export async function runScenario(scenario: Scenario, target: Target): Promise<StepOutcome[]> {
  const clients = new Map<string, Client>();
  const outcomes: StepOutcome[] = [];
  try {
    for (const [index, step] of scenario.steps.entries()) {
      const label = step.name ?? `${index + 1}. ${step.server}.${step.tool}`;
      const url = urlFor(target, step);
      const client = clients.get(url) ?? (await connect(url, target));
      clients.set(url, client);
      outcomes.push(await runStep(client, target, step, label));
    }
  } finally {
    await Promise.all([...clients.values()].map((client) => client.close()));
  }
  return outcomes;
}

/**
 * Resolves the MCP endpoint a step's call goes to.
 *
 * @param target - direct servers or the gateway
 * @param step - the step to route
 * @returns the endpoint URL
 * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
 */
function urlFor(target: Target, step: Step): string {
  if (target.kind === 'gateway') {
    return target.url;
  }
  const url = target.urls[step.server];
  if (url === undefined) {
    throw new Error(`No URL configured for server ${step.server}`);
  }
  return url;
}

/**
 * Opens an MCP client on one endpoint, sending the gateway key when there is one.
 *
 * @param url - the MCP endpoint
 * @param target - supplies the API key in gateway mode
 * @returns a connected client
 * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
 */
async function connect(url: string, target: Target): Promise<Client> {
  const headers: Record<string, string> = {};
  if (target.kind === 'gateway' && target.apiKey) {
    headers['authorization'] = `Bearer ${target.apiKey}`;
  }
  const versionNegotiation = target.legacy ? undefined : { mode: { pin: '2026-07-28' as const } };
  const client = new Client({ name: 'aduana-scripted-agent', version: '0.1.0' }, { versionNegotiation });
  await client.connect(new StreamableHTTPClientTransport(new URL(url), { requestInit: { headers } }));
  return client;
}

/**
 * Makes one call and compares its result with the step's expectations.
 *
 * @param client - client connected to the step's endpoint
 * @param target - decides the tool name the call uses
 * @param step - the call and its expectations
 * @param label - how the step appears in the report
 * @returns the outcome
 * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
 */
async function runStep(client: Client, target: Target, step: Step, label: string): Promise<StepOutcome> {
  const name = target.kind === 'gateway' ? `${step.server}__${step.tool}` : step.tool;
  try {
    const params = step.meta
      ? { name, arguments: step.arguments, _meta: step.meta }
      : { name, arguments: step.arguments };
    const result = (await client.callTool(params)) as ToolResult;
    const text = (result.content ?? []).map((block) => block.text ?? '').join('\n');
    const problems = check(step.expect, result, text);
    return { step: label, passed: problems.length === 0, problems, text };
  } catch (error: unknown) {
    const message = error instanceof Error ? error.message : String(error);
    return { step: label, passed: false, problems: [`call failed: ${message}`], text: '' };
  }
}

/**
 * Lists every expectation a result breaks.
 *
 * @param expect - the step's expectations; `is_error` defaults to false
 * @param result - the tool result
 * @param text - the result's text blocks joined
 * @returns human-readable problems, empty when the step passed
 * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
 */
export function check(expect: Expectation, result: ToolResult, text: string): string[] {
  const problems: string[] = [];
  const wantError = expect.is_error ?? expect.error_code !== undefined;
  if ((result.isError ?? false) !== wantError) {
    problems.push(`expected isError=${wantError}, got ${result.isError ?? false}: ${text.slice(0, 200)}`);
  }
  for (const fragment of expect.text_contains ?? []) {
    if (!text.includes(fragment)) {
      problems.push(`text does not contain "${fragment}"`);
    }
  }
  for (const fragment of expect.text_excludes ?? []) {
    if (text.includes(fragment)) {
      problems.push(`text contains "${fragment}"`);
    }
  }
  if (expect.error_code !== undefined) {
    const structured = result.structuredContent as { code?: unknown } | undefined;
    if (structured?.code !== expect.error_code) {
      problems.push(`expected error code ${expect.error_code}, got ${String(structured?.code)}`);
    }
  }
  return problems;
}

/**
 * Parses `--direct crm=url,payments=url` or `--gateway url`, an optional `--legacy`, and the scenario paths.
 *
 * The agent speaks 2026-07-28 unless `--legacy` asks for the 2025-11-25 `initialize` handshake.
 *
 * @param argv - command-line arguments after the script name
 * @returns the target and the scenario files
 * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
 */
export function parseArguments(argv: readonly string[]): { target: Target; files: string[] } {
  const files: string[] = [];
  let target: Target | undefined;
  const legacy = argv.includes('--legacy');
  for (let index = 0; index < argv.length; index += 1) {
    const argument = argv[index];
    const value = argv[index + 1] ?? '';
    if (argument === '--direct') {
      const urls = Object.fromEntries(value.split(',').map((pair) => pair.split('=', 2) as [string, string]));
      target = { kind: 'direct', urls, legacy };
      index += 1;
    } else if (argument === '--gateway') {
      target = { kind: 'gateway', url: value, apiKey: process.env['ADUANA_API_KEY'], legacy };
      index += 1;
    } else if (argument !== undefined && argument !== '--legacy') {
      files.push(argument);
    }
  }
  if (!target || files.length === 0) {
    throw new Error('usage: agent (--direct name=url,... | --gateway url) <scenario.yaml>...');
  }
  return { target, files };
}

/**
 * Runs every scenario given on the command line and exits 1 if any step failed.
 *
 * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
 */
export async function main(): Promise<void> {
  const { target, files } = parseArguments(process.argv.slice(2));
  let failed = 0;
  for (const file of files) {
    const scenario = await loadScenario(file);
    const outcomes = await runScenario(scenario, target);
    console.log(`scenario ${scenario.name}`);
    for (const outcome of outcomes) {
      console.log(`  ${outcome.passed ? 'pass' : 'FAIL'} ${outcome.step}`);
      outcome.problems.forEach((problem) => console.log(`       ${problem}`));
      failed += outcome.passed ? 0 : 1;
    }
  }
  console.log(failed === 0 ? 'all steps passed' : `${failed} steps failed`);
  process.exitCode = failed === 0 ? 0 : 1;
}

if (import.meta.url === `file://${process.argv[1]}`) {
  await main();
}
