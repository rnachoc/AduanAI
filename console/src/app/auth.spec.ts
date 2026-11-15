// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Ignacio Rodríguez Caro
import { TestBed } from '@angular/core/testing';
import { Router, type UrlTree } from '@angular/router';
import { Auth, hasRole, requireRole } from './auth';
import { answer, setUpHttp } from './testing';

describe('hasRole', () => {
  it('lets each role include the ones below it', () => {
    expect(hasRole({ email: 'a@example.com', role: 'admin' }, 'approver')).toBe(true);
    expect(hasRole({ email: 'a@example.com', role: 'approver' }, 'viewer')).toBe(true);
    expect(hasRole({ email: 'a@example.com', role: 'viewer' }, 'approver')).toBe(false);
    expect(hasRole(null, 'viewer')).toBe(false);
  });
});

describe('Auth', () => {
  it('signs in, keeps the user in memory only, and signs out', async () => {
    const http = setUpHttp();
    const auth = TestBed.inject(Auth);
    const login = auth.login('finance@example.com', 'secret');
    const sent = await answer(http, 'POST', '/api/v1/auth/login', {
      email: 'finance@example.com',
      role: 'approver',
    });
    expect(sent.body).toEqual({ email: 'finance@example.com', password: 'secret' });
    await answer(http, 'GET', '/api/v1/auth/me', {
      email: 'finance@example.com',
      role: 'approver',
    });
    await login;
    expect(auth.signedIn()).toBe(true);
    expect(auth.can('approver')).toBe(true);
    expect(auth.can('admin')).toBe(false);
    expect(localStorage.length).toBe(0);
    expect(sessionStorage.length).toBe(0);
    const logout = auth.logout();
    await answer(http, 'POST', '/api/v1/auth/logout', null);
    await logout;
    expect(auth.user()).toBeNull();
  });

  it('restores nobody when the session is gone', async () => {
    const http = setUpHttp();
    const auth = TestBed.inject(Auth);
    const restored = auth.restore();
    await answer(http, 'GET', '/api/v1/auth/me', null, 401);
    expect(await restored).toBeNull();
    expect(await auth.restore()).toBeNull();
    http.expectNone('/api/v1/auth/me');
  });
});

describe('requireRole', () => {
  it('sends anonymous users to sign in and viewers away from approvals', async () => {
    const http = setUpHttp();
    const router = TestBed.inject(Router);
    const anonymous = TestBed.runInInjectionContext(() =>
      requireRole('viewer')({} as never, {} as never),
    );
    await answer(http, 'GET', '/api/v1/auth/me', null, 401);
    expect(router.serializeUrl((await anonymous) as UrlTree)).toBe('/login');
  });

  it('lets a viewer see activity but not approvals', async () => {
    const http = setUpHttp();
    const router = TestBed.inject(Router);
    const approvals = TestBed.runInInjectionContext(() =>
      requireRole('approver')({} as never, {} as never),
    );
    await answer(http, 'GET', '/api/v1/auth/me', { email: 'viewer@example.com', role: 'viewer' });
    expect(router.serializeUrl((await approvals) as UrlTree)).toBe('/activity');
    const activity = TestBed.runInInjectionContext(() =>
      requireRole('viewer')({} as never, {} as never),
    );
    expect(await activity).toBe(true);
  });
});
