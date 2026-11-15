// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Ignacio Rodríguez Caro
import { Component, type OnInit, computed, inject, signal } from '@angular/core';
import { type ActivePolicy, Api } from '../api';

/**
 * The active policy bundle, read only, and the result of its tests.
 *
 * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
 */
@Component({
  selector: 'app-policy',
  template: `
    <header class="page-head">
      <p class="eyebrow">Active bundle</p>
      <h1>Policy</h1>
      <p class="lede">
        The rules in force and the result of their tests. Load a new bundle through the API or the
        CLI.
      </p>
    </header>
    @if (policy(); as active) {
      <p class="banner" [class.bad]="failed().length > 0" role="status">
        Bundle {{ active.sha256.slice(0, 12) }}… loaded {{ active.loadedAt }}:
        {{ active.results.length - failed().length }} of {{ active.results.length }} tests pass
      </p>
      @if (failed().length > 0) {
        <h2>Failing tests</h2>
        <ul>
          @for (result of failed(); track result.file + result.name) {
            <li>{{ result.file }} · {{ result.name }}: {{ result.problems.join('; ') }}</li>
          }
        </ul>
      }
      <h2>policy.yaml</h2>
      <pre tabindex="0">{{ active.policy }}</pre>
    } @else {
      <p class="muted">{{ message() }}</p>
    }
  `,
})
export class PolicyPage implements OnInit {
  private readonly api = inject(Api);
  protected readonly policy = signal<ActivePolicy | null>(null);
  protected readonly message = signal('Loading…');
  protected readonly failed = computed(() =>
    (this.policy()?.results ?? []).filter((result) => result.problems.length > 0),
  );

  /**
   * Loads the active bundle.
   *
   * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
   */
  async ngOnInit(): Promise<void> {
    try {
      this.policy.set(await this.api.policy());
    } catch {
      this.message.set('No policy is active: every call is denied.');
    }
  }
}
