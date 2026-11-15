// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Ignacio Rodríguez Caro
import { Component, type OnInit, inject, signal } from '@angular/core';
import { Api, type ApiKey, type Principal } from '../api';

/**
 * Identities: agent principals with their attributes, and each one's API keys.
 *
 * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
 */
@Component({
  selector: 'app-identities',
  template: `
    <h1>Identities</h1>
    <div class="split">
      <table>
        <caption class="visually-hidden">
          Principals
        </caption>
        <thead>
          <tr>
            <th scope="col">Name</th>
            <th scope="col">Kind</th>
            <th scope="col">Attributes</th>
            <th scope="col">Status</th>
          </tr>
        </thead>
        <tbody>
          @for (principal of principals(); track principal.id) {
            <tr>
              <td>
                <button type="button" class="link" (click)="open(principal)">
                  {{ principal.name }}
                </button>
              </td>
              <td>{{ principal.kind }}</td>
              <td>{{ attributes(principal) }}</td>
              <td>{{ principal.status }}</td>
            </tr>
          }
        </tbody>
      </table>
      @if (selected(); as principal) {
        <section class="detail" aria-labelledby="keys-title">
          <h2 id="keys-title">Keys of {{ principal.name }}</h2>
          <ul class="list">
            @for (key of keys(); track key.id) {
              <li>
                <code>{{ key.prefix }}…</code>
                <span class="muted"
                  >created {{ key.createdAt
                  }}{{ key.revokedAt ? ', revoked ' + key.revokedAt : '' }}</span
                >
                @if (!key.revokedAt) {
                  <button type="button" class="danger" (click)="revoke(principal, key)">
                    Revoke
                  </button>
                }
              </li>
            } @empty {
              <li class="muted">No keys.</li>
            }
          </ul>
        </section>
      }
    </div>
  `,
})
export class IdentitiesPage implements OnInit {
  private readonly api = inject(Api);
  protected readonly principals = signal<Principal[]>([]);
  protected readonly selected = signal<Principal | null>(null);
  protected readonly keys = signal<ApiKey[]>([]);

  /**
   * Loads the principals.
   *
   * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
   */
  async ngOnInit(): Promise<void> {
    this.principals.set(await this.api.principals());
  }

  /**
   * Shows a principal's keys.
   *
   * @param principal - the principal
   * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
   */
  protected async open(principal: Principal): Promise<void> {
    this.selected.set(principal);
    this.keys.set(await this.api.keys(principal.id));
  }

  /**
   * Revokes a key and reloads the list.
   *
   * @param principal - the key's principal
   * @param key - the key
   * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
   */
  protected async revoke(principal: Principal, key: ApiKey): Promise<void> {
    await this.api.revokeKey(key.id);
    this.keys.set(await this.api.keys(principal.id));
  }

  /**
   * Prints a principal's attributes.
   *
   * @param principal - the principal
   * @returns `key=value, …`
   * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
   */
  protected attributes(principal: Principal): string {
    return Object.entries(principal.attributes)
      .map(([key, value]) => `${key}=${value}`)
      .join(', ');
  }
}
