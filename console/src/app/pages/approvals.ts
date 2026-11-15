// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Ignacio Rodríguez Caro
import { Component, type OnInit, inject, signal } from '@angular/core';
import { Api, type Approval } from '../api';
import { Auth } from '../auth';
import { prettyJson } from '../diff';

/**
 * The approval queue: pending calls the user's groups may decide, with redacted arguments, the
 * rules that asked for approval and the call's flags.
 *
 * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
 */
@Component({
  selector: 'app-approvals',
  template: `
    <header class="page-head">
      <p class="eyebrow">Waiting for a person</p>
      <h1>Approvals</h1>
      <p class="lede">
        Calls the policy held for your group, with arguments redacted the way the log keeps them.
      </p>
    </header>
    <div class="split">
      <section aria-labelledby="queue-title">
        <h2 id="queue-title">Pending ({{ pending().length }})</h2>
        <ul class="list">
          @for (approval of pending(); track approval.id) {
            <li>
              <button
                type="button"
                class="row"
                [class.selected]="approval.id === selected()?.id"
                (click)="selected.set(approval)"
              >
                <strong>{{ approval.tool }}</strong>
                <span class="muted"
                  >{{ approval.principal }} · expires {{ approval.expiresAt }}</span
                >
              </button>
            </li>
          } @empty {
            <li class="muted empty">No calls waiting.</li>
          }
        </ul>
      </section>
      @if (selected(); as approval) {
        <section aria-labelledby="detail-title" class="detail">
          <h2 id="detail-title">{{ approval.tool }}</h2>
          <dl>
            <dt>Agent</dt>
            <dd>{{ approval.principal }} {{ attributes(approval) }}</dd>
            <dt>Approvers</dt>
            <dd>{{ approval.approversGroup }}</dd>
            <dt>Rules</dt>
            <dd>{{ approval.rules.join(', ') }}</dd>
            <dt>Flags</dt>
            <dd>
              @for (flag of approval.flags; track flag) {
                <span
                  class="tag"
                  [class.warn]="flag.startsWith('FLOW_FROM') || flag.startsWith('TAINT')"
                  >{{ flag }}</span
                >
              } @empty {
                none
              }
            </dd>
            <dt>Requested</dt>
            <dd>{{ approval.createdAt }}</dd>
          </dl>
          <h3>Arguments (redacted)</h3>
          <pre tabindex="0">{{ json(approval.arguments) }}</pre>
          @if (auth.can('approver')) {
            <div class="actions">
              <button type="button" (click)="decide(approval, 'approve')">Approve</button>
              <button type="button" class="danger" (click)="decide(approval, 'reject')">
                Reject
              </button>
            </div>
          }
        </section>
      }
    </div>
    @if (message()) {
      <p role="status">{{ message() }}</p>
    }
  `,
})
export class ApprovalsPage implements OnInit {
  private readonly api = inject(Api);
  protected readonly auth = inject(Auth);
  protected readonly pending = signal<Approval[]>([]);
  protected readonly selected = signal<Approval | null>(null);
  protected readonly message = signal('');

  /**
   * Loads the queue.
   *
   * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
   */
  async ngOnInit(): Promise<void> {
    await this.reload();
  }

  /**
   * Reloads the pending approvals and keeps the selection when it is still pending.
   *
   * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
   */
  async reload(): Promise<void> {
    const pending = await this.api.approvals('pending');
    this.pending.set(pending);
    const id = this.selected()?.id;
    this.selected.set(pending.find((approval) => approval.id === id) ?? pending[0] ?? null);
  }

  /**
   * Approves or rejects the selected call.
   *
   * @param approval - the approval
   * @param decision - what to do
   * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
   */
  protected async decide(approval: Approval, decision: 'approve' | 'reject'): Promise<void> {
    try {
      await this.api.decide(approval.id, decision);
      this.message.set(decision === 'approve' ? 'Approved.' : 'Rejected.');
      this.selected.set(null);
      await this.reload();
    } catch {
      this.message.set('The decision was not accepted. Reload and try again.');
    }
  }

  /**
   * Prints the redacted arguments.
   *
   * @param value - the arguments
   * @returns the JSON text
   * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
   */
  protected json(value: unknown): string {
    return prettyJson(value);
  }

  /**
   * Prints an agent's attributes.
   *
   * @param approval - the approval
   * @returns `(key=value, …)` or nothing
   * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
   */
  protected attributes(approval: Approval): string {
    const pairs = Object.entries(approval.principalAttributes).map(
      ([key, value]) => `${key}=${value}`,
    );
    return pairs.length === 0 ? '' : `(${pairs.join(', ')})`;
  }
}
