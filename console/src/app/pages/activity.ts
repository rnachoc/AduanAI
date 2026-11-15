// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Ignacio Rodríguez Caro
import { Component, type OnDestroy, type OnInit, inject, signal } from '@angular/core';
import { Api } from '../api';

/** One line of live activity. */
export interface ActivityItem {
  readonly seq: number;
  readonly ts: string;
  readonly type: string;
  readonly tool: string | null;
  readonly decision: string | null;
  readonly reasonCode: string | null;
}

const KEEP = 100;

/**
 * Live activity: the latest log entries, then every new one as the gateway appends it (SSE).
 *
 * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
 */
@Component({
  selector: 'app-activity',
  template: `
    <h1>Activity</h1>
    <p class="muted" aria-live="polite">
      {{ live() ? 'Live' : 'Connecting…' }} · {{ items().length }} entries
    </p>
    <table>
      <caption class="visually-hidden">
        Latest log entries, newest first
      </caption>
      <thead>
        <tr>
          <th scope="col">Seq</th>
          <th scope="col">Time</th>
          <th scope="col">Type</th>
          <th scope="col">Tool</th>
          <th scope="col">Decision</th>
          <th scope="col">Reason</th>
        </tr>
      </thead>
      <tbody>
        @for (item of items(); track item.seq) {
          <tr>
            <td>{{ item.seq }}</td>
            <td>{{ item.ts }}</td>
            <td>
              <span class="tag">{{ item.type }}</span>
            </td>
            <td>{{ item.tool ?? '' }}</td>
            <td>{{ item.decision ?? '' }}</td>
            <td>{{ item.reasonCode ?? '' }}</td>
          </tr>
        } @empty {
          <tr>
            <td colspan="6">Nothing yet.</td>
          </tr>
        }
      </tbody>
    </table>
  `,
})
export class ActivityPage implements OnInit, OnDestroy {
  private readonly api = inject(Api);
  private source: EventSource | null = null;
  protected readonly items = signal<ActivityItem[]>([]);
  protected readonly live = signal(false);

  /**
   * Loads the last entries and subscribes to new ones.
   *
   * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
   */
  async ngOnInit(): Promise<void> {
    const verification = await this.api.verify();
    const entries = await this.api.audit({ fromSeq: verification.lastSeq - 49, limit: 50 });
    this.items.set(
      entries
        .map((entry) => ({
          seq: entry.seq,
          ts: entry.ts,
          type: entry.type,
          tool: entry.tool,
          decision: entry.decision,
          reasonCode: entry.reason_code,
        }))
        .reverse(),
    );
    if (typeof EventSource === 'function') {
      this.source = new EventSource('/api/v1/events');
      this.source.onopen = () => this.live.set(true);
      this.source.onerror = () => this.live.set(false);
      this.source.addEventListener('audit', (event) =>
        this.add(JSON.parse((event as MessageEvent<string>).data)),
      );
    }
  }

  /**
   * Adds an appended entry at the top.
   *
   * @param item - the entry as the gateway announced it
   * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
   */
  add(item: ActivityItem): void {
    this.items.update((items) =>
      [item, ...items.filter((old) => old.seq !== item.seq)].slice(0, KEEP),
    );
  }

  /**
   * Closes the stream.
   *
   * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
   */
  ngOnDestroy(): void {
    this.source?.close();
  }
}
