// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Ignacio Rodríguez Caro
import { readFile } from 'node:fs/promises';
import { parse } from 'yaml';
import * as z from 'zod';

const expectationSchema = z
  .object({
    is_error: z.boolean().optional(),
    text_contains: z.array(z.string()).optional(),
    text_excludes: z.array(z.string()).optional(),
    error_code: z.string().optional(),
  })
  .strict();

const stepSchema = z
  .object({
    name: z.string().optional(),
    server: z.string().regex(/^[a-z][a-z0-9-]*$/),
    tool: z.string().regex(/^[a-z][a-z0-9_]*$/),
    arguments: z.record(z.string(), z.unknown()).default({}),
    meta: z.record(z.string(), z.unknown()).optional(),
    expect: expectationSchema.default({}),
  })
  .strict();

const scenarioSchema = z
  .object({
    name: z.string(),
    description: z.string(),
    steps: z.array(stepSchema).min(1),
  })
  .strict();

/** What a step expects back from its call. */
export type Expectation = z.infer<typeof expectationSchema>;

/** One scripted tool call. */
export type Step = z.infer<typeof stepSchema>;

/** A named list of scripted tool calls. */
export type Scenario = z.infer<typeof scenarioSchema>;

/**
 * Parses and validates a scenario written in YAML.
 *
 * @param text - the YAML source
 * @returns the validated scenario
 * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
 */
export function parseScenario(text: string): Scenario {
  return scenarioSchema.parse(parse(text));
}

/**
 * Reads and validates a scenario file.
 *
 * @param path - path to a `.yaml` scenario
 * @returns the validated scenario
 * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
 */
export async function loadScenario(path: string): Promise<Scenario> {
  return parseScenario(await readFile(path, 'utf8'));
}
