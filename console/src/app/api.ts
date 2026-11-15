// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Ignacio Rodríguez Caro
import { HttpClient, HttpParams } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { firstValueFrom } from 'rxjs';

/** A console role; each includes the ones before it. */
export type Role = 'viewer' | 'approver' | 'admin';

/** The signed-in user. */
export interface Me {
  readonly email: string;
  readonly role: Role;
}

/** An approval as the queue shows it, arguments already redacted by the gateway. */
export interface Approval {
  readonly id: string;
  readonly state: string;
  readonly tool: string;
  readonly principal: string;
  readonly principalAttributes: Record<string, string>;
  readonly arguments: unknown;
  readonly rules: readonly string[];
  readonly flags: readonly string[];
  readonly approversGroup: string;
  readonly createdAt: string;
  readonly expiresAt: string;
  readonly decidedAt: string | null;
}

/** One log entry, with the field names of `docs/audit-format.md`. */
export interface AuditEntry {
  readonly seq: number;
  readonly ts: string;
  readonly type: string;
  readonly principal_id: string | null;
  readonly tool: string | null;
  readonly decision: string | null;
  readonly reason_code: string | null;
  readonly matched_rules: readonly string[] | null;
  readonly args_redacted: unknown;
  readonly flags: readonly string[];
  readonly hash: string;
  readonly [field: string]: unknown;
}

/** Filters for the log search. */
export interface AuditQuery {
  readonly fromSeq?: number;
  readonly limit?: number;
  readonly tool?: string;
  readonly decision?: string;
  readonly principal?: string;
}

/** The chain's verification and its newest checkpoint. */
export interface Verification {
  readonly entries: number;
  readonly lastSeq: number;
  readonly checkpoints: number;
  readonly problems: readonly { seq: number; kind: string; detail: string }[];
  readonly intact: boolean;
  readonly lastCheckpoint: { seq: number; ts: string } | null;
}

/** An upstream server in the catalog. */
export interface Server {
  readonly id: string;
  readonly name: string;
  readonly transport: string;
  readonly endpoint: string;
  readonly strict: boolean;
  readonly status: string;
  readonly timeoutMs: number;
  readonly hasCredential: boolean;
}

/** A tool in the catalog, with the definition waiting for review if there is one. */
export interface Tool {
  readonly id: string;
  readonly server: string;
  readonly name: string;
  readonly definition: unknown;
  readonly definitionSha256: string;
  readonly pendingDefinition: unknown;
  readonly sideEffect: boolean;
  readonly untrustedSource: boolean;
  readonly sensitivity: string;
  readonly cost: number;
  readonly enabled: boolean;
  readonly state: string;
  readonly descriptionFlags: readonly string[];
}

/** The active policy bundle and the result of its tests. */
export interface ActivePolicy {
  readonly sha256: string;
  readonly loadedAt: string;
  readonly policy: string;
  readonly tests: Record<string, string>;
  readonly results: readonly { file: string; name: string; problems: readonly string[] }[];
}

/** An agent or user principal. */
export interface Principal {
  readonly id: string;
  readonly kind: string;
  readonly name: string;
  readonly attributes: Record<string, string>;
  readonly groups: readonly string[];
  readonly status: string;
}

/** An API key; the secret is only present right after creation. */
export interface ApiKey {
  readonly id: string;
  readonly prefix: string;
  readonly createdAt: string;
  readonly expiresAt: string | null;
  readonly revokedAt: string | null;
  readonly secret: string | null;
}

/**
 * Typed calls to the administration API. The session cookie and the CSRF header travel with
 * Angular's HTTP client; nothing is kept in browser storage.
 *
 * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
 */
@Injectable({ providedIn: 'root' })
export class Api {
  private readonly http = inject(HttpClient);

  /**
   * Signs in.
   *
   * @param email - the user's address
   * @param password - the password
   * @returns the signed-in user
   * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
   */
  login(email: string, password: string): Promise<Me> {
    return firstValueFrom(this.http.post<Me>('/api/v1/auth/login', { email, password }));
  }

  /**
   * Signs out.
   *
   * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
   */
  async logout(): Promise<void> {
    await firstValueFrom(this.http.post('/api/v1/auth/logout', {}));
  }

  /**
   * Reads the signed-in user; also sets the CSRF cookie.
   *
   * @returns the user
   * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
   */
  me(): Promise<Me> {
    return firstValueFrom(this.http.get<Me>('/api/v1/auth/me'));
  }

  /**
   * Lists approvals the user may decide.
   *
   * @param state - only this state, such as `pending`
   * @returns the approvals
   * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
   */
  approvals(state: string): Promise<Approval[]> {
    const params = new HttpParams().set('state', state);
    return firstValueFrom(this.http.get<Approval[]>('/api/v1/approvals', { params }));
  }

  /**
   * Approves or rejects a pending approval.
   *
   * @param id - the approval
   * @param decision - `approve` or `reject`
   * @returns the approval after the decision
   * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
   */
  decide(id: string, decision: 'approve' | 'reject'): Promise<Approval> {
    return firstValueFrom(this.http.post<Approval>(`/api/v1/approvals/${id}/${decision}`, {}));
  }

  /**
   * Searches the log.
   *
   * @param query - the filters
   * @returns entries in `seq` order
   * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
   */
  audit(query: AuditQuery): Promise<AuditEntry[]> {
    let params = new HttpParams()
      .set('from_seq', String(Math.max(1, query.fromSeq ?? 1)))
      .set('limit', String(query.limit ?? 50));
    for (const [name, value] of [
      ['tool', query.tool],
      ['decision', query.decision],
      ['principal', query.principal],
    ] as const) {
      if (value) {
        params = params.set(name, value);
      }
    }
    return firstValueFrom(this.http.get<AuditEntry[]>('/api/v1/audit', { params }));
  }

  /**
   * Verifies the chain on the server.
   *
   * @returns the verification
   * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
   */
  verify(): Promise<Verification> {
    return firstValueFrom(this.http.get<Verification>('/api/v1/audit/verify'));
  }

  /**
   * Lists the upstream servers.
   *
   * @returns the servers
   * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
   */
  servers(): Promise<Server[]> {
    return firstValueFrom(this.http.get<Server[]>('/api/v1/servers'));
  }

  /**
   * Lists the catalog's tools.
   *
   * @returns the tools
   * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
   */
  tools(): Promise<Tool[]> {
    return firstValueFrom(this.http.get<Tool[]>('/api/v1/tools'));
  }

  /**
   * Accepts the definition a tool's server now publishes.
   *
   * @param id - the tool
   * @returns the tool after the change
   * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
   */
  acceptDefinition(id: string): Promise<Tool> {
    return firstValueFrom(this.http.patch<Tool>(`/api/v1/tools/${id}`, { acceptDefinition: true }));
  }

  /**
   * Reads the active policy and its test results.
   *
   * @returns the policy
   * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
   */
  policy(): Promise<ActivePolicy> {
    return firstValueFrom(this.http.get<ActivePolicy>('/api/v1/policy'));
  }

  /**
   * Lists the principals.
   *
   * @returns the principals
   * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
   */
  principals(): Promise<Principal[]> {
    return firstValueFrom(this.http.get<Principal[]>('/api/v1/principals'));
  }

  /**
   * Lists a principal's API keys.
   *
   * @param id - the principal
   * @returns the keys, without secrets
   * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
   */
  keys(id: string): Promise<ApiKey[]> {
    return firstValueFrom(this.http.get<ApiKey[]>(`/api/v1/principals/${id}/keys`));
  }

  /**
   * Revokes an API key.
   *
   * @param id - the key
   * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
   */
  async revokeKey(id: string): Promise<void> {
    await firstValueFrom(this.http.delete(`/api/v1/keys/${id}`));
  }
}
