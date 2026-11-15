// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Ignacio Rodríguez Caro
import { Component, type OnInit, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { Api, type Simulation } from '../api';

/**
 * Simulation: replays logged decisions against a candidate policy before anyone loads it. Decisions
 * that depend on redacted arguments are listed apart, never counted as unchanged.
 *
 * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
 */
@Component({
  selector: 'app-simulation',
  imports: [FormsModule],
  template: `
    <header class="page-head">
      <p class="eyebrow">Before you load it</p>
      <h1>Simulation</h1>
      <p class="lede">
        Replay recorded decisions against a candidate policy and see what would change.
      </p>
    </header>
    <form class="card" (ngSubmit)="run()">
      <label for="candidate">Candidate policy.yaml</label>
      <textarea
        id="candidate"
        name="candidate"
        rows="16"
        [ngModel]="policy()"
        (ngModelChange)="policy.set($event)"
      ></textarea>
      <label for="from">From log entry (empty: the newest 20 000)</label>
      <input id="from" name="from" type="number" min="1" [(ngModel)]="fromSeq" />
      <button type="submit" [disabled]="busy()">Simulate</button>
    </form>
    @if (errors().length > 0) {
      <div class="banner bad" role="alert">
        The candidate does not parse:
        <ul>
          @for (error of errors(); track $index) {
            <li>{{ error }}</li>
          }
        </ul>
      </div>
    }
    @if (result(); as sim) {
      <p class="banner" [class.bad]="sim.changed > 0" role="status">
        Log entries {{ sim.firstSeq }} to {{ sim.lastSeq }}, {{ sim.decisions }} decisions:
        {{ sim.changed }} would change, {{ sim.unchanged }} unchanged, {{ sim.unknown }} unknown
      </p>
      <h2>Changes</h2>
      <div class="panel">
        <table>
          <caption class="visually-hidden">
            Decisions that would change, grouped by the candidate's rules
          </caption>
          <thead>
            <tr>
              <th scope="col">Rules</th>
              <th scope="col">Before</th>
              <th scope="col">After</th>
              <th scope="col">Count</th>
              <th scope="col">Examples</th>
            </tr>
          </thead>
          <tbody>
            @for (change of sim.changes; track change.rules + change.before + change.after) {
              <tr>
                <td>{{ change.rules }}</td>
                <td>{{ change.before }}</td>
                <td>{{ change.after }}</td>
                <td>{{ change.count }}</td>
                <td>{{ examples(change.examples) }}</td>
              </tr>
            } @empty {
              <tr>
                <td colspan="5">No decision would change.</td>
              </tr>
            }
          </tbody>
        </table>
      </div>
      <h2>Unknown ({{ sim.unknown }})</h2>
      <p class="muted">
        The log keeps arguments redacted, so a condition that reads a redacted value cannot be
        replayed.
      </p>
      <ul>
        @for (unknown of sim.unknowns; track unknown.seq) {
          <li>seq {{ unknown.seq }} · {{ unknown.tool }} · {{ unknown.reason }}</li>
        }
      </ul>
    }
  `,
})
export class SimulationPage implements OnInit {
  private readonly api = inject(Api);
  protected readonly policy = signal('');
  protected fromSeq: number | null = null;
  protected readonly busy = signal(false);
  protected readonly result = signal<Simulation | null>(null);
  protected readonly errors = signal<string[]>([]);

  /**
   * Starts from the active policy.
   *
   * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
   */
  async ngOnInit(): Promise<void> {
    try {
      this.policy.set((await this.api.policy()).policy);
    } catch {
      this.policy.set('');
    }
  }

  /**
   * Runs the simulation.
   *
   * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
   */
  async run(): Promise<void> {
    this.busy.set(true);
    this.errors.set([]);
    try {
      this.result.set(
        await this.api.simulate(this.policy(), this.fromSeq ? Number(this.fromSeq) : null),
      );
    } catch (failure: unknown) {
      const body = (failure as { error?: { errors?: string[] } }).error;
      this.result.set(null);
      this.errors.set(body?.errors ?? ['The gateway refused the simulation.']);
    } finally {
      this.busy.set(false);
    }
  }

  /**
   * Prints a group's example entries.
   *
   * @param examples - the examples
   * @returns `seq 12 (agent), …`
   * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
   */
  protected examples(examples: Simulation['changes'][number]['examples']): string {
    return examples.map((example) => `seq ${example.seq} (${example.principal})`).join(', ');
  }
}
