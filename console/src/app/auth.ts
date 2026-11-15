// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Ignacio Rodríguez Caro
import { Injectable, computed, inject, signal } from '@angular/core';
import { type CanActivateFn, Router } from '@angular/router';
import { Api, type Me, type Role } from './api';

const RANK: Record<Role, number> = { viewer: 0, approver: 1, admin: 2 };

/**
 * Tells whether a user's role includes the required one.
 *
 * @param user - the signed-in user, or null
 * @param required - the role a screen or action needs
 * @returns true when the user has that role or a higher one
 * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
 */
export function hasRole(user: Me | null, required: Role): boolean {
  return user !== null && RANK[user.role] >= RANK[required];
}

/**
 * Holds who is signed in. The session lives in an `HttpOnly` cookie the page cannot read; this
 * service only remembers the answer of `/auth/me` in memory.
 *
 * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
 */
@Injectable({ providedIn: 'root' })
export class Auth {
  private readonly api = inject(Api);
  private readonly current = signal<Me | null>(null);
  private checked = false;

  /** The signed-in user, or null. */
  readonly user = this.current.asReadonly();

  /** True when someone is signed in. */
  readonly signedIn = computed(() => this.current() !== null);

  /**
   * Asks the gateway who is signed in, once per page load.
   *
   * @returns the user, or null when there is no session
   * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
   */
  async restore(): Promise<Me | null> {
    if (!this.checked) {
      this.checked = true;
      try {
        this.current.set(await this.api.me());
      } catch {
        this.current.set(null);
      }
    }
    return this.current();
  }

  /**
   * Signs in and loads the CSRF cookie for the requests that follow.
   *
   * @param email - the address
   * @param password - the password
   * @returns the user
   * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
   */
  async login(email: string, password: string): Promise<Me> {
    await this.api.login(email, password);
    const me = await this.api.me();
    this.checked = true;
    this.current.set(me);
    return me;
  }

  /**
   * Signs out.
   *
   * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
   */
  async logout(): Promise<void> {
    try {
      await this.api.logout();
    } finally {
      this.current.set(null);
    }
  }

  /**
   * Tells whether the signed-in user has a role.
   *
   * @param required - the role
   * @returns true when allowed
   * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
   */
  can(required: Role): boolean {
    return hasRole(this.current(), required);
  }
}

/**
 * Builds a route guard that sends anonymous users to sign in and others to the activity screen
 * when their role is too low.
 *
 * @param required - the role the route needs
 * @returns the guard
 * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
 */
export function requireRole(required: Role): CanActivateFn {
  return async () => {
    const auth = inject(Auth);
    const router = inject(Router);
    const user = await auth.restore();
    if (user === null) {
      return router.parseUrl('/login');
    }
    return hasRole(user, required) ? true : router.parseUrl('/activity');
  };
}
