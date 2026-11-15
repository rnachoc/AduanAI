// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Ignacio Rodríguez Caro
import { Component, inject, signal } from '@angular/core';
import { Router, RouterLink, RouterLinkActive, RouterOutlet } from '@angular/router';
import { Auth } from './auth';

/**
 * The console's frame: header, navigation by role, theme switch and the routed screen.
 *
 * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
 */
@Component({
  selector: 'app-root',
  imports: [RouterOutlet, RouterLink, RouterLinkActive],
  template: `
    <a class="skip" href="#main" (click)="skip($event)">Skip to content</a>
    <header class="top">
      <span class="brand">Aduana</span>
      @if (auth.user(); as user) {
        <nav aria-label="Main">
          <a routerLink="/activity" routerLinkActive="active">Activity</a>
          @if (auth.can('approver')) {
            <a routerLink="/approvals" routerLinkActive="active">Approvals</a>
          }
          <a routerLink="/audit" routerLinkActive="active">Log</a>
          @if (auth.can('admin')) {
            <a routerLink="/catalog" routerLinkActive="active">Catalog</a>
            <a routerLink="/policy" routerLinkActive="active">Policy</a>
            <a routerLink="/simulation" routerLinkActive="active">Simulation</a>
            <a routerLink="/identities" routerLinkActive="active">Identities</a>
          }
        </nav>
        <span class="who">{{ user.email }} · {{ user.role }}</span>
        <button type="button" class="quiet" (click)="logout()">Sign out</button>
      } @else {
        <span class="spacer"></span>
      }
      <button type="button" class="quiet" [attr.aria-pressed]="dark()" (click)="toggleTheme()">
        {{ dark() ? 'Light' : 'Dark' }} theme
      </button>
    </header>
    <main id="main" tabindex="-1">
      <router-outlet />
    </main>
  `,
})
export class App {
  protected readonly auth = inject(Auth);
  private readonly router = inject(Router);
  protected readonly dark = signal(
    typeof matchMedia === 'function' && matchMedia('(prefers-color-scheme: dark)').matches,
  );

  /**
   * Applies the system theme on start.
   *
   * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
   */
  constructor() {
    this.applyTheme();
  }

  /**
   * Switches between light and dark for this page load.
   *
   * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
   */
  protected toggleTheme(): void {
    this.dark.update((value) => !value);
    this.applyTheme();
  }

  /**
   * Moves focus to the main region without changing the route.
   *
   * @param event - the click on the skip link
   * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
   */
  protected skip(event: Event): void {
    event.preventDefault();
    document.getElementById('main')?.focus();
  }

  /**
   * Signs out and returns to the sign-in screen.
   *
   * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
   */
  protected async logout(): Promise<void> {
    await this.auth.logout();
    await this.router.navigateByUrl('/login');
  }

  /**
   * Sets the theme attribute the stylesheet reads.
   *
   * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
   */
  private applyTheme(): void {
    document.documentElement.dataset['theme'] = this.dark() ? 'dark' : 'light';
  }
}
