// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Ignacio Rodríguez Caro

/** One line of a diff: kept, removed from the old text or added in the new one. */
export interface DiffLine {
  readonly kind: 'same' | 'removed' | 'added';
  readonly text: string;
}

/**
 * Prints a JSON value with sorted keys and two-space indentation, so equal values print equally.
 *
 * @param value - any JSON value
 * @returns the printed text
 * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
 */
export function prettyJson(value: unknown): string {
  return JSON.stringify(sortKeys(value), null, 2) ?? 'null';
}

/**
 * Copies a JSON value with every object's keys in order.
 *
 * @param value - the value
 * @returns the sorted copy
 * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
 */
function sortKeys(value: unknown): unknown {
  if (Array.isArray(value)) {
    return value.map(sortKeys);
  }
  if (value !== null && typeof value === 'object') {
    const entries = Object.entries(value as Record<string, unknown>).sort(([a], [b]) =>
      a < b ? -1 : a > b ? 1 : 0,
    );
    return Object.fromEntries(entries.map(([key, inner]) => [key, sortKeys(inner)]));
  }
  return value;
}

/**
 * Diffs two texts line by line with a longest common subsequence.
 *
 * @param before - the old text
 * @param after - the new text
 * @returns the lines in order, each marked kept, removed or added
 * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
 */
export function diffLines(before: string, after: string): DiffLine[] {
  const a = before.split('\n');
  const b = after.split('\n');
  const lengths: number[][] = Array.from({ length: a.length + 1 }, () =>
    new Array<number>(b.length + 1).fill(0),
  );
  for (let i = a.length - 1; i >= 0; i--) {
    for (let j = b.length - 1; j >= 0; j--) {
      lengths[i][j] =
        a[i] === b[j] ? lengths[i + 1][j + 1] + 1 : Math.max(lengths[i + 1][j], lengths[i][j + 1]);
    }
  }
  const lines: DiffLine[] = [];
  let i = 0;
  let j = 0;
  while (i < a.length && j < b.length) {
    if (a[i] === b[j]) {
      lines.push({ kind: 'same', text: a[i] });
      i++;
      j++;
    } else if (lengths[i + 1][j] >= lengths[i][j + 1]) {
      lines.push({ kind: 'removed', text: a[i++] });
    } else {
      lines.push({ kind: 'added', text: b[j++] });
    }
  }
  while (i < a.length) {
    lines.push({ kind: 'removed', text: a[i++] });
  }
  while (j < b.length) {
    lines.push({ kind: 'added', text: b[j++] });
  }
  return lines;
}
