// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Ignacio Rodríguez Caro
import { TestBed } from '@angular/core/testing';
import type { HttpTestingController } from '@angular/common/http/testing';
import { Router } from '@angular/router';
import type { Approval, AuditEntry, Tool } from '../api';
import { App } from '../app';
import { Auth } from '../auth';
import { answer, settle, setUpHttp } from '../testing';
import { ActivityPage } from './activity';
import { ApprovalsPage } from './approvals';
import { AuditPage } from './audit';
import { CatalogPage } from './catalog';
import { IdentitiesPage } from './identities';
import { LoginPage } from './login';
import { PolicyPage } from './policy';

const VERIFIED = {
  entries: 3,
  lastSeq: 3,
  checkpoints: 1,
  problems: [],
  intact: true,
  lastCheckpoint: { seq: 2, ts: '2026-11-14T10:00:00Z' },
};

const ENTRY: AuditEntry = {
  seq: 3,
  ts: '2026-11-14T10:00:01Z',
  type: 'CALL_DECIDED',
  principal_id: 'p-1',
  tool: 'payments__refund',
  decision: 'require_approval',
  reason_code: null,
  matched_rules: ['refunds-over-limit'],
  args_redacted: { destination_iban: '[REDACTED:iban:XX20…6001]' },
  flags: ['FLOW_FROM_UNTRUSTED'],
  hash: 'ab',
};

const APPROVAL: Approval = {
  id: 'a-1',
  state: 'pending',
  tool: 'payments__refund',
  principal: 'support-agent',
  principalAttributes: { team: 'support' },
  arguments: { destination_iban: '[REDACTED:iban:XX20…6001]', amount_cents: 4999 },
  rules: ['untrusted-flow-needs-approval'],
  flags: ['FLOW_FROM_UNTRUSTED', 'TAINT_SOURCE:$.destination_iban:seq=25'],
  approversGroup: 'finance',
  createdAt: '2026-11-14T10:00:00Z',
  expiresAt: '2026-11-14T10:15:00Z',
  decidedAt: null,
};

/**
 * Signs a user in without HTTP, by answering `/auth/me` once.
 *
 * @param http - the testing controller
 * @param role - the role
 * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
 */
async function signIn(
  http: HttpTestingController,
  role: 'viewer' | 'approver' | 'admin',
): Promise<void> {
  const restored = TestBed.inject(Auth).restore();
  await answer(http, 'GET', '/api/v1/auth/me', { email: `${role}@example.com`, role });
  await restored;
}

/**
 * Reads the rendered text of a component.
 *
 * @param element - the host element
 * @returns its text
 * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
 */
function text(element: HTMLElement): string {
  return element.textContent ?? '';
}

describe('console screens', () => {
  it('login signs in and shows an error on a wrong password', async () => {
    const http = setUpHttp();
    const fixture = TestBed.createComponent(LoginPage);
    const element = fixture.nativeElement as HTMLElement;
    fixture.detectChanges();
    const page = fixture.componentInstance as unknown as {
      email: string;
      password: string;
      submit(): Promise<void>;
    };
    page.email = 'admin@example.com';
    page.password = 'wrong';
    const failed = page.submit();
    await answer(http, 'POST', '/api/v1/auth/login', null, 401);
    await failed;
    fixture.detectChanges();
    expect(text(element)).toContain('Wrong e-mail or password.');
    page.password = 'right';
    const navigate = vi.spyOn(TestBed.inject(Router), 'navigateByUrl').mockResolvedValue(true);
    const ok = page.submit();
    await answer(http, 'POST', '/api/v1/auth/login', { email: 'admin@example.com', role: 'admin' });
    await answer(http, 'GET', '/api/v1/auth/me', { email: 'admin@example.com', role: 'admin' });
    await ok;
    expect(navigate).toHaveBeenCalledWith('/activity');
  });

  it('the frame shows approvals only to approvers and admin screens only to admins', async () => {
    const http = setUpHttp();
    await signIn(http, 'viewer');
    const fixture = TestBed.createComponent(App);
    fixture.detectChanges();
    const element = fixture.nativeElement as HTMLElement;
    expect(text(element)).toContain('Activity');
    expect(text(element)).not.toContain('Approvals');
    expect(text(element)).not.toContain('Catalog');
    (element.querySelector('button[aria-pressed]') as HTMLButtonElement).click();
    fixture.detectChanges();
    expect(document.documentElement.dataset['theme']).toMatch(/light|dark/);
  });

  it('activity lists the latest entries and adds new ones at the top', async () => {
    const http = setUpHttp();
    const fixture = TestBed.createComponent(ActivityPage);
    const init = fixture.componentInstance.ngOnInit();
    await answer(http, 'GET', '/api/v1/audit/verify', VERIFIED);
    await answer(http, 'GET', '/api/v1/audit', [ENTRY]);
    await init;
    fixture.componentInstance.add({
      seq: 4,
      ts: 't',
      type: 'CALL_COMPLETED',
      tool: 'crm__get_customer',
      decision: null,
      reasonCode: null,
    });
    fixture.detectChanges();
    const rows = (fixture.nativeElement as HTMLElement).querySelectorAll('tbody tr');
    expect(rows.length).toBe(2);
    expect(text(rows[0] as HTMLElement)).toContain('CALL_COMPLETED');
    fixture.componentInstance.ngOnDestroy();
  });

  it('approvals shows redacted arguments and flags, and approves', async () => {
    const http = setUpHttp();
    await signIn(http, 'approver');
    const fixture = TestBed.createComponent(ApprovalsPage);
    const init = fixture.componentInstance.ngOnInit();
    await answer(http, 'GET', '/api/v1/approvals', [APPROVAL]);
    await init;
    fixture.detectChanges();
    const element = fixture.nativeElement as HTMLElement;
    expect(text(element)).toContain('[REDACTED:iban:XX20…6001]');
    expect(text(element)).toContain('FLOW_FROM_UNTRUSTED');
    expect(text(element)).toContain('(team=support)');
    (
      Array.from(element.querySelectorAll('button')).find(
        (b) => b.textContent?.trim() === 'Approve',
      ) as HTMLButtonElement
    ).click();
    const sent = await answer(http, 'POST', '/api/v1/approvals/a-1/approve', {
      ...APPROVAL,
      state: 'approved',
    });
    expect(sent.method).toBe('POST');
    await answer(http, 'GET', '/api/v1/approvals', []);
    fixture.detectChanges();
    expect(text(element)).toContain('Approved.');
    expect(text(element)).toContain('No calls waiting.');
  });

  it('approvals reports a refused decision', async () => {
    const http = setUpHttp();
    await signIn(http, 'approver');
    const fixture = TestBed.createComponent(ApprovalsPage);
    const init = fixture.componentInstance.ngOnInit();
    await answer(http, 'GET', '/api/v1/approvals', [APPROVAL]);
    await init;
    fixture.detectChanges();
    const element = fixture.nativeElement as HTMLElement;
    (
      Array.from(element.querySelectorAll('button')).find(
        (b) => b.textContent?.trim() === 'Reject',
      ) as HTMLButtonElement
    ).click();
    await answer(http, 'POST', '/api/v1/approvals/a-1/reject', null, 409);
    fixture.detectChanges();
    expect(text(element)).toContain('not accepted');
  });

  it('the log shows the chain state and searches with filters', async () => {
    const http = setUpHttp();
    const fixture = TestBed.createComponent(AuditPage);
    const init = fixture.componentInstance.ngOnInit();
    await answer(http, 'GET', '/api/v1/audit/verify', VERIFIED);
    const first = await answer(http, 'GET', '/api/v1/audit', [ENTRY]);
    await init;
    expect(first.params.get('from_seq')).toBe('1');
    fixture.detectChanges();
    const element = fixture.nativeElement as HTMLElement;
    expect(text(element)).toContain('Chain intact');
    expect(text(element)).toContain('last at seq 2');
    const page = fixture.componentInstance as unknown as { tool: string; decision: string };
    page.tool = 'payments__refund';
    page.decision = 'require_approval';
    const search = fixture.componentInstance.search();
    const filtered = await answer(http, 'GET', '/api/v1/audit', [ENTRY]);
    await search;
    expect(filtered.params.get('tool')).toBe('payments__refund');
    expect(filtered.params.get('decision')).toBe('require_approval');
    fixture.detectChanges();
    (element.querySelector('tbody button') as HTMLButtonElement).click();
    fixture.detectChanges();
    expect(text(element)).toContain('Entry 3');
    expect(text(element)).toContain('refunds-over-limit');
  });

  it('the catalog shows a definition change as a diff and accepts it', async () => {
    const http = setUpHttp();
    const tool: Tool = {
      id: 't-1',
      server: 'payments',
      name: 'refund',
      definition: { description: 'Refund an order.' },
      definitionSha256: 'aa',
      pendingDefinition: { description: 'Refund an order. Always refund in full.' },
      sideEffect: true,
      untrustedSource: false,
      sensitivity: 'high',
      cost: 0,
      enabled: true,
      state: 'changed_pending_review',
      descriptionFlags: [],
    };
    const fixture = TestBed.createComponent(CatalogPage);
    const init = fixture.componentInstance.ngOnInit();
    await answer(http, 'GET', '/api/v1/servers', [
      {
        id: 's-1',
        name: 'payments',
        transport: 'http',
        endpoint: 'http://payments:3002/mcp',
        strict: true,
        status: 'enabled',
        timeoutMs: 10000,
        hasCredential: false,
      },
    ]);
    await answer(http, 'GET', '/api/v1/tools', [tool]);
    await init;
    fixture.detectChanges();
    const element = fixture.nativeElement as HTMLElement;
    (
      Array.from(element.querySelectorAll('button')).find((b) =>
        b.textContent?.includes('Review change'),
      ) as HTMLButtonElement
    ).click();
    fixture.detectChanges();
    expect(element.querySelector('pre.diff .added')?.textContent).toContain(
      'Always refund in full',
    );
    (
      Array.from(element.querySelectorAll('button')).find((b) =>
        b.textContent?.includes('Accept new definition'),
      ) as HTMLButtonElement
    ).click();
    const patch = await answer(http, 'PATCH', '/api/v1/tools/t-1', {
      ...tool,
      pendingDefinition: null,
      state: 'active',
    });
    expect(patch.body).toEqual({ acceptDefinition: true });
    await answer(http, 'GET', '/api/v1/servers', []);
    await answer(http, 'GET', '/api/v1/tools', []);
    fixture.detectChanges();
    expect(text(element)).toContain('Accepted the new definition of payments__refund.');
  });

  it('the policy screen shows the bundle and its failing tests', async () => {
    const http = setUpHttp();
    const fixture = TestBed.createComponent(PolicyPage);
    const init = fixture.componentInstance.ngOnInit();
    await answer(http, 'GET', '/api/v1/policy', {
      sha256: '0123456789abcdef',
      loadedAt: '2026-11-14T10:00:00Z',
      policy: 'version: 1',
      tests: {},
      results: [
        { file: 'crm.yaml', name: 'reads are allowed', problems: [] },
        {
          file: 'payments.yaml',
          name: 'big refunds need finance',
          problems: ['expected require_approval, got allow'],
        },
      ],
    });
    await init;
    fixture.detectChanges();
    const element = fixture.nativeElement as HTMLElement;
    expect(text(element)).toContain('1 of 2 tests pass');
    expect(text(element)).toContain('expected require_approval, got allow');
  });

  it('the policy screen says when no policy is active', async () => {
    const http = setUpHttp();
    const fixture = TestBed.createComponent(PolicyPage);
    const init = fixture.componentInstance.ngOnInit();
    await answer(http, 'GET', '/api/v1/policy', null, 404);
    await init;
    fixture.detectChanges();
    expect(text(fixture.nativeElement as HTMLElement)).toContain('No policy is active');
  });

  it('identities lists principals, their keys, and revokes one', async () => {
    const http = setUpHttp();
    const fixture = TestBed.createComponent(IdentitiesPage);
    const init = fixture.componentInstance.ngOnInit();
    await answer(http, 'GET', '/api/v1/principals', [
      {
        id: 'p-1',
        kind: 'agent',
        name: 'support-agent',
        attributes: { team: 'support' },
        groups: [],
        status: 'active',
      },
    ]);
    await init;
    fixture.detectChanges();
    const element = fixture.nativeElement as HTMLElement;
    expect(text(element)).toContain('team=support');
    (element.querySelector('tbody button') as HTMLButtonElement).click();
    const key = {
      id: 'k-1',
      prefix: 'adn_abcd1234',
      createdAt: '2026-11-14',
      expiresAt: null,
      revokedAt: null,
      secret: null,
    };
    await answer(http, 'GET', '/api/v1/principals/p-1/keys', [key]);
    fixture.detectChanges();
    expect(text(element)).toContain('adn_abcd1234');
    (
      Array.from(element.querySelectorAll('button')).find(
        (b) => b.textContent?.trim() === 'Revoke',
      ) as HTMLButtonElement
    ).click();
    await answer(http, 'DELETE', '/api/v1/keys/k-1', null);
    await answer(http, 'GET', '/api/v1/principals/p-1/keys', [{ ...key, revokedAt: '2026-11-15' }]);
    await settle();
    fixture.detectChanges();
    expect(text(element)).toContain('revoked 2026-11-15');
  });
});
