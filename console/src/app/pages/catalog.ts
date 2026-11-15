// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Ignacio Rodríguez Caro
import { Component, type OnInit, computed, inject, signal } from '@angular/core';
import { Api, type Server, type Tool } from '../api';
import { type DiffLine, diffLines, prettyJson } from '../diff';

/**
 * The catalog: servers, tools with their metadata, and the review of definitions a server changed.
 *
 * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
 */
@Component({
  selector: 'app-catalog',
  template: `
    <h1>Catalog</h1>
    <h2>Servers</h2>
    <table>
      <caption class="visually-hidden">
        Upstream servers
      </caption>
      <thead>
        <tr>
          <th scope="col">Name</th>
          <th scope="col">Transport</th>
          <th scope="col">Endpoint</th>
          <th scope="col">Status</th>
          <th scope="col">Credential</th>
        </tr>
      </thead>
      <tbody>
        @for (server of servers(); track server.id) {
          <tr>
            <td>{{ server.name }}</td>
            <td>{{ server.transport }}</td>
            <td>
              <code>{{ server.endpoint }}</code>
            </td>
            <td>{{ server.status }}</td>
            <td>{{ server.hasCredential ? 'yes' : 'no' }}</td>
          </tr>
        }
      </tbody>
    </table>
    <h2>Tools</h2>
    <table>
      <caption class="visually-hidden">
        Tools offered to agents
      </caption>
      <thead>
        <tr>
          <th scope="col">Tool</th>
          <th scope="col">State</th>
          <th scope="col">Side effect</th>
          <th scope="col">Untrusted source</th>
          <th scope="col">Sensitivity</th>
          <th scope="col">Cost</th>
          <th scope="col">Marks</th>
          <th scope="col">Review</th>
        </tr>
      </thead>
      <tbody>
        @for (tool of tools(); track tool.id) {
          <tr>
            <td>{{ tool.server }}__{{ tool.name }}</td>
            <td>
              <span class="tag" [class.warn]="tool.state !== 'active'">{{ tool.state }}</span>
            </td>
            <td>{{ tool.sideEffect ? 'yes' : 'no' }}</td>
            <td>{{ tool.untrustedSource ? 'yes' : 'no' }}</td>
            <td>{{ tool.sensitivity }}</td>
            <td>{{ tool.cost }}</td>
            <td>{{ tool.descriptionFlags.join(', ') }}</td>
            <td>
              @if (tool.pendingDefinition) {
                <button type="button" (click)="review.set(tool)">Review change</button>
              }
            </td>
          </tr>
        }
      </tbody>
    </table>
    @if (review(); as tool) {
      <section class="detail" aria-labelledby="review-title">
        <h2 id="review-title">Definition change: {{ tool.server }}__{{ tool.name }}</h2>
        <p class="muted">
          Removed lines are what agents see now; added lines are what the server publishes.
        </p>
        <pre
          class="diff"
          tabindex="0"
        >@for (line of diff(); track $index) {<span [class]="line.kind">{{ prefix(line) }}{{ line.text }}</span>
}</pre>
        <div class="actions">
          <button type="button" (click)="accept(tool)">Accept new definition</button>
          <button type="button" class="quiet" (click)="review.set(null)">Close</button>
        </div>
      </section>
    }
    @if (message()) {
      <p role="status">{{ message() }}</p>
    }
  `,
})
export class CatalogPage implements OnInit {
  private readonly api = inject(Api);
  protected readonly servers = signal<Server[]>([]);
  protected readonly tools = signal<Tool[]>([]);
  protected readonly review = signal<Tool | null>(null);
  protected readonly message = signal('');
  protected readonly diff = computed(() => {
    const tool = this.review();
    return tool === null
      ? []
      : diffLines(prettyJson(tool.definition), prettyJson(tool.pendingDefinition));
  });

  /**
   * Loads servers and tools.
   *
   * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
   */
  async ngOnInit(): Promise<void> {
    await this.reload();
  }

  /**
   * Reloads servers and tools.
   *
   * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
   */
  async reload(): Promise<void> {
    const [servers, tools] = await Promise.all([this.api.servers(), this.api.tools()]);
    this.servers.set(servers);
    this.tools.set(tools);
  }

  /**
   * Accepts the reviewed definition.
   *
   * @param tool - the tool
   * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
   */
  protected async accept(tool: Tool): Promise<void> {
    await this.api.acceptDefinition(tool.id);
    this.review.set(null);
    this.message.set(`Accepted the new definition of ${tool.server}__${tool.name}.`);
    await this.reload();
  }

  /**
   * The diff marker of a line.
   *
   * @param line - the line
   * @returns `-`, `+` or a space
   * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
   */
  protected prefix(line: DiffLine): string {
    return line.kind === 'removed' ? '- ' : line.kind === 'added' ? '+ ' : '  ';
  }
}
