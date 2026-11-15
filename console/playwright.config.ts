// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Ignacio Rodríguez Caro
import { defineConfig } from '@playwright/test';

/** Flows against the running stack (`make console-e2e`); the gateway URL comes from the environment. */
export default defineConfig({
  testDir: 'e2e',
  fullyParallel: false,
  workers: 1,
  retries: 0,
  reporter: [['list']],
  use: {
    baseURL: process.env['CONSOLE_URL'] ?? 'http://localhost:8780',
    viewport: { width: 1360, height: 860 },
    colorScheme: 'dark',
    trace: 'off',
  },
});
