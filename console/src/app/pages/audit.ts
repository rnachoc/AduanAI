// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Ignacio Rodríguez Caro
import { Component, type OnInit, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { Api, type AuditEntry, type Verification } from '../api';
import { prettyJson } from '../diff';

/**
 * The log: the chain's verification with its last checkpoint, a search by tool, decision and
 * agent, and each entry in full.
 *
 * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
 */
@Component({
  selector: 'app-audit',
  imports: [FormsModule],
  template: `
    <h1>Log</h1>
    @if (verification(); as check) {
      <p class="banner" [class.bad]="!check.intact" role="status">
        Chain {{ check.intact ? 'intact' : 'BROKEN' }}: {{ check.entries }} entries, last seq
        {{ check.lastSeq }}, {{ check.checkpoints }} signed checkpoints
        @if (check.lastCheckpoint; as last) {
          , last at seq {{ last.seq }} ({{ last.ts }})
        }
        @for (problem of check.problems; track $index) {
          <br />{{ problem.kind }} at seq {{ problem.seq }}
        }
      </p>
    } @else {
      <p class="banner" role="status">Verifying the chain and its signed checkpoints…</p>
    }
    <form class="filters" (ngSubmit)="search()">
      <label for="tool">Tool</label>
      <input id="tool" name="tool" [(ngModel)]="tool" placeholder="payments__refund" />
      <label for="decision">Decision</label>
      <select id="decision" name="decision" [(ngModel)]="decision">
        <option value="">any</option>
        <option value="allow">allow</option>
        <option value="require_approval">require_approval</option>
        <option value="deny">deny</option>
      </select>
      <label for="principal">Agent id</label>
      <input id="principal" name="principal" [(ngModel)]="principal" />
      <button type="submit">Search</button>
    </form>
    <div class="split">
      <table>
        <caption class="visually-hidden">
          Matching entries, newest first
        </caption>
        <thead>
          <tr>
            <th scope="col">Seq</th>
            <th scope="col">Type</th>
            <th scope="col">Tool</th>
            <th scope="col">Decision</th>
            <th scope="col">Flags</th>
          </tr>
        </thead>
        <tbody>
          @for (entry of entries(); track entry.seq) {
            <tr>
              <td>
                <button type="button" class="link" (click)="selected.set(entry)">
                  {{ entry.seq }}
                </button>
              </td>
              <td>{{ entry.type }}</td>
              <td>{{ entry.tool ?? '' }}</td>
              <td>{{ entry.decision ?? '' }}</td>
              <td>{{ entry.flags.length }}</td>
            </tr>
          } @empty {
            <tr>
              <td colspan="5">No entries match.</td>
            </tr>
          }
        </tbody>
      </table>
      @if (selected(); as entry) {
        <section class="detail" aria-labelledby="entry-title">
          <h2 id="entry-title">Entry {{ entry.seq }}</h2>
          <pre tabindex="0">{{ json(entry) }}</pre>
        </section>
      }
    </div>
  `,
})
export class AuditPage implements OnInit {
  private readonly api = inject(Api);
  protected readonly verification = signal<Verification | null>(null);
  protected readonly entries = signal<AuditEntry[]>([]);
  protected readonly selected = signal<AuditEntry | null>(null);
  protected tool = '';
  protected decision = '';
  protected principal = '';
  private lastSeq = 0;

  /**
   * Verifies the chain and lists the latest entries.
   *
   * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
   */
  async ngOnInit(): Promise<void> {
    this.lastSeq = (await this.api.head()).lastSeq;
    const verifying = this.api.verify().then((verification) => this.verification.set(verification));
    await this.search();
    await verifying;
  }

  /**
   * Runs the search: the newest 200 entries that match, newest first.
   *
   * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
   */
  async search(): Promise<void> {
    const filtered = this.tool !== '' || this.decision !== '' || this.principal !== '';
    const last = this.lastSeq;
    const entries = await this.api.audit({
      fromSeq: filtered ? 1 : last - 199,
      limit: 200,
      tool: this.tool.trim(),
      decision: this.decision,
      principal: this.principal.trim(),
    });
    this.entries.set([...entries].reverse());
    this.selected.set(null);
  }

  /**
   * Prints an entry.
   *
   * @param value - the entry
   * @returns the JSON text
   * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
   */
  protected json(value: unknown): string {
    return prettyJson(value);
  }
}
