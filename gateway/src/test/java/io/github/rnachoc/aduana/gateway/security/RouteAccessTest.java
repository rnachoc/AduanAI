// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Ignacio Rodríguez Caro
package io.github.rnachoc.aduana.gateway.security;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.rnachoc.aduana.gateway.support.TestDatabase;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpMethod;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

/**
 * Every route a controller declares must have a row in {@link RouteAccess}, and every row must
 * point at a real route, so the table cannot drift from the code.
 *
 * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
 */
@SpringBootTest(
    properties = {
      "aduana.master-key=local-only",
      "aduana.catalog.sync-interval=PT1H",
      "aduana.policy.directory=src/test/resources/policies/allow-all"
    })
@Import(TestDatabase.class)
class RouteAccessTest {

  @Autowired
  @Qualifier("requestMappingHandlerMapping")
  private RequestMappingHandlerMapping mappings;

  /**
   * Lists every method and pattern the controllers declare.
   *
   * @return pairs as `METHOD pattern`
   * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
   */
  private List<String> declaredRoutes() {
    List<String> routes = new ArrayList<>();
    for (RequestMappingInfo info : mappings.getHandlerMethods().keySet()) {
      for (String pattern : info.getPatternValues()) {
        if (pattern.equals("/error")) {
          continue;
        }
        for (RequestMethod method : info.getMethodsCondition().getMethods()) {
          routes.add(method.name() + " " + pattern);
        }
      }
    }
    return routes;
  }

  @Test
  void every_declared_route_has_a_row_in_the_access_table() {
    List<String> missing =
        declaredRoutes().stream()
            .filter(route -> {
              String[] parts = route.split(" ", 2);
              return RouteAccess.find(HttpMethod.valueOf(parts[0]), parts[1]).isEmpty();
            })
            .toList();
    assertThat(missing).as("routes without a RouteAccess row").isEmpty();
  }

  @Test
  void every_row_in_the_access_table_is_a_declared_route() {
    List<String> declared = declaredRoutes();
    List<String> stale =
        RouteAccess.ROUTES.stream()
            .map(route -> route.method().name() + " " + route.pattern())
            .filter(route -> !declared.contains(route))
            .toList();
    assertThat(stale).as("RouteAccess rows with no controller").isEmpty();
  }

  @Test
  void a_route_without_a_row_is_detected() {
    assertThat(RouteAccess.find(HttpMethod.POST, "/api/v1/everything")).isEmpty();
  }
}
