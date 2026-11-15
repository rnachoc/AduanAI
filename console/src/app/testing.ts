// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Ignacio Rodríguez Caro
import type { HttpRequest } from '@angular/common/http';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { routes } from './app.routes';

/**
 * Configures the test bed with the console's routes and a fake HTTP backend.
 *
 * @returns the controller that answers requests
 * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
 */
export function setUpHttp(): HttpTestingController {
  TestBed.configureTestingModule({
    providers: [provideRouter(routes), provideHttpClient(), provideHttpClientTesting()],
  });
  return TestBed.inject(HttpTestingController);
}

/**
 * Waits for a request to a URL, answers it and lets the caller's promise continue.
 *
 * @param http - the testing controller
 * @param method - the HTTP method
 * @param url - the path, without query
 * @param body - the answer
 * @param status - the HTTP status; 200 by default
 * @returns the request that was answered
 * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
 */
export async function answer(
  http: HttpTestingController,
  method: string,
  url: string,
  body: string | number | boolean | object | null,
  status = 200,
): Promise<HttpRequest<unknown>> {
  for (let attempt = 0; attempt < 50; attempt++) {
    const found = http.match((request) => request.method === method && request.url === url);
    if (found.length > 0) {
      const request = found[0];
      if (status === 200) {
        request.flush(body);
      } else {
        request.flush(body, { status, statusText: 'Error' });
      }
      await settle();
      return request.request;
    }
    await settle();
  }
  throw new Error(`no ${method} ${url}`);
}

/**
 * Lets pending promise callbacks run.
 *
 * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
 */
export async function settle(): Promise<void> {
  for (let i = 0; i < 5; i++) {
    await Promise.resolve();
  }
}
