// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Ignacio Rodríguez Caro
import { Component, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { Router } from '@angular/router';
import { Auth } from '../auth';

/**
 * Sign-in with e-mail and password; the gateway answers with an `HttpOnly` session cookie.
 *
 * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
 */
@Component({
  selector: 'app-login',
  imports: [FormsModule],
  template: `
    <section class="narrow">
      <h1>Sign in</h1>
      <form (ngSubmit)="submit()">
        <label for="email">E-mail</label>
        <input
          id="email"
          name="email"
          type="email"
          autocomplete="username"
          required
          [(ngModel)]="email"
        />
        <label for="password">Password</label>
        <input
          id="password"
          name="password"
          type="password"
          autocomplete="current-password"
          required
          [(ngModel)]="password"
        />
        <button type="submit" [disabled]="busy()">Sign in</button>
        @if (error()) {
          <p class="error" role="alert">{{ error() }}</p>
        }
      </form>
    </section>
  `,
})
export class LoginPage {
  private readonly auth = inject(Auth);
  private readonly router = inject(Router);
  protected email = '';
  protected password = '';
  protected readonly busy = signal(false);
  protected readonly error = signal('');

  /**
   * Signs in and opens the activity screen.
   *
   * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
   */
  protected async submit(): Promise<void> {
    this.busy.set(true);
    this.error.set('');
    try {
      await this.auth.login(this.email, this.password);
      this.password = '';
      await this.router.navigateByUrl('/activity');
    } catch {
      this.error.set('Wrong e-mail or password.');
    } finally {
      this.busy.set(false);
    }
  }
}
