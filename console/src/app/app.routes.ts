// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Ignacio Rodríguez Caro
import type { Routes } from '@angular/router';
import { requireRole } from './auth';
import { ActivityPage } from './pages/activity';
import { ApprovalsPage } from './pages/approvals';
import { AuditPage } from './pages/audit';
import { CatalogPage } from './pages/catalog';
import { IdentitiesPage } from './pages/identities';
import { LoginPage } from './pages/login';
import { PolicyPage } from './pages/policy';
import { SimulationPage } from './pages/simulation';

/** The console's screens and the role each needs. */
export const routes: Routes = [
  { path: 'login', component: LoginPage, title: 'Sign in · Aduana' },
  {
    path: 'activity',
    component: ActivityPage,
    canActivate: [requireRole('viewer')],
    title: 'Activity · Aduana',
  },
  {
    path: 'approvals',
    component: ApprovalsPage,
    canActivate: [requireRole('approver')],
    title: 'Approvals · Aduana',
  },
  {
    path: 'audit',
    component: AuditPage,
    canActivate: [requireRole('viewer')],
    title: 'Log · Aduana',
  },
  {
    path: 'catalog',
    component: CatalogPage,
    canActivate: [requireRole('admin')],
    title: 'Catalog · Aduana',
  },
  {
    path: 'policy',
    component: PolicyPage,
    canActivate: [requireRole('admin')],
    title: 'Policy · Aduana',
  },
  {
    path: 'simulation',
    component: SimulationPage,
    canActivate: [requireRole('admin')],
    title: 'Simulation · Aduana',
  },
  {
    path: 'identities',
    component: IdentitiesPage,
    canActivate: [requireRole('admin')],
    title: 'Identities · Aduana',
  },
  { path: '', pathMatch: 'full', redirectTo: 'activity' },
  { path: '**', redirectTo: 'activity' },
];
