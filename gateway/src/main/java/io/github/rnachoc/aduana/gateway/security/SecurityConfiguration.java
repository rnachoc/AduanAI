// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Ignacio Rodríguez Caro
package io.github.rnachoc.aduana.gateway.security;

import io.github.rnachoc.aduana.gateway.events.EventLog;
import io.github.rnachoc.aduana.gateway.identity.PrincipalService;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AuthorizeHttpRequestsConfigurer;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtDecoders;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;

/**
 * Wires authentication and authorisation: API keys or JWT on `/mcp`, session cookies with CSRF
 * tokens on `/api/v1`, and every rule taken from {@link RouteAccess}.
 *
 * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
 */
@Configuration
public class SecurityConfiguration {

  /** The console's static files, served to anyone; its data needs a session. */
  static final String[] CONSOLE_FILES = {
    "/", "/index.html", "/*.js", "/*.css", "/*.ico", "/fonts/**", "/media/**"
  };

  /**
   * The MCP endpoint: no session, no CSRF (bearer tokens only); the agent filter authenticates.
   *
   * @param http the security builder
   * @return the chain
   * @throws Exception if the chain cannot be built
   * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
   */
  @Bean
  @Order(1)
  public SecurityFilterChain mcpChain(HttpSecurity http) throws Exception {
    return http.securityMatcher("/mcp", "/mcp/**")
        .csrf(csrf -> csrf.disable())
        .sessionManagement(session -> session.disable())
        .requestCache(cache -> cache.disable())
        .authorizeHttpRequests(authorize -> authorize.anyRequest().permitAll())
        .build();
  }

  /**
   * The administration API: session cookie, CSRF double-submit token, roles from the table.
   *
   * @param http the security builder
   * @return the chain
   * @throws Exception if the chain cannot be built
   * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
   */
  @Bean
  @Order(2)
  public SecurityFilterChain apiChain(HttpSecurity http) throws Exception {
    CsrfTokenRequestAttributeHandler handler = new CsrfTokenRequestAttributeHandler();
    handler.setCsrfRequestAttributeName(null);
    return http.securityMatcher("/api/**")
        .csrf(csrf ->
            csrf.csrfTokenRepository(CookieCsrfTokenRepository.withHttpOnlyFalse())
                .csrfTokenRequestHandler(handler)
                .ignoringRequestMatchers("/api/v1/auth/login"))
        .securityContext(context -> context.securityContextRepository(sessionRepository()))
        .requestCache(cache -> cache.disable())
        .exceptionHandling(exceptions ->
            exceptions
                .authenticationEntryPoint((request, response, failure) ->
                    Problems.write(
                        response, HttpServletResponse.SC_UNAUTHORIZED, "UNAUTHENTICATED"))
                .accessDeniedHandler((request, response, failure) ->
                    Problems.write(response, HttpServletResponse.SC_FORBIDDEN, "FORBIDDEN")))
        .authorizeHttpRequests(this::applyRouteTable)
        .build();
  }

  /**
   * Everything else: health and well-known documents are public, the rest is denied.
   *
   * @param http the security builder
   * @return the chain
   * @throws Exception if the chain cannot be built
   * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
   */
  @Bean
  @Order(3)
  public SecurityFilterChain defaultChain(HttpSecurity http) throws Exception {
    return http.csrf(csrf -> csrf.disable())
        .sessionManagement(session -> session.disable())
        .authorizeHttpRequests(authorize ->
            authorize
                .requestMatchers(HttpMethod.GET, "/healthz", "/.well-known/**")
                .permitAll()
                .requestMatchers(HttpMethod.GET, CONSOLE_FILES)
                .permitAll()
                .requestMatchers("/error")
                .permitAll()
                .anyRequest()
                .denyAll())
        .build();
  }

  /**
   * Stores the signed-in user in the HTTP session.
   *
   * @return the repository the login controller also writes to
   * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
   */
  @Bean
  public HttpSessionSecurityContextRepository sessionRepository() {
    return new HttpSessionSecurityContextRepository();
  }

  /**
   * Adds the content security policy and related headers to every response, before any chain.
   *
   * @return the filter registration
   * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
   */
  @Bean
  public FilterRegistrationBean<SecurityHeadersFilter> securityHeaders() {
    FilterRegistrationBean<SecurityHeadersFilter> registration =
        new FilterRegistrationBean<>(new SecurityHeadersFilter());
    registration.addUrlPatterns("/*");
    registration.setOrder(Integer.MIN_VALUE);
    return registration;
  }

  /**
   * Registers the agent filter on the MCP endpoint only.
   *
   * @param principals authenticates keys
   * @param jwtDecoder validates JWTs when configured
   * @param events records failures
   * @return the registration
   * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
   */
  @Bean
  public FilterRegistrationBean<AgentAuthenticationFilter> agentAuthentication(
      PrincipalService principals, ObjectProvider<JwtDecoder> jwtDecoder, EventLog events) {
    FilterRegistrationBean<AgentAuthenticationFilter> registration =
        new FilterRegistrationBean<>(new AgentAuthenticationFilter(principals, jwtDecoder, events));
    registration.addUrlPatterns("/mcp", "/mcp/*");
    return registration;
  }

  /**
   * Validates agent JWTs against the issuer's published keys, only when an issuer is configured.
   *
   * @param issuer the OIDC issuer URL
   * @return the decoder
   * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
   */
  @Bean
  @ConditionalOnProperty("aduana.oidc.issuer-uri")
  public JwtDecoder jwtDecoder(
      @org.springframework.beans.factory.annotation.Value("${aduana.oidc.issuer-uri}")
          String issuer) {
    return JwtDecoders.fromIssuerLocation(issuer);
  }

  /**
   * Turns each API row of the route table into an authorisation rule; anything else is denied.
   *
   * @param authorize the authorisation registry
   * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
   */
  private void applyRouteTable(
      AuthorizeHttpRequestsConfigurer<HttpSecurity>.AuthorizationManagerRequestMatcherRegistry
          authorize) {
    for (RouteAccess.Route route : RouteAccess.ROUTES) {
      if (!route.pattern().startsWith("/api/")) {
        continue;
      }
      var rule = authorize.requestMatchers(route.method(), route.pattern());
      switch (route.access()) {
        case PUBLIC -> rule.permitAll();
        case SIGNED_IN -> rule.authenticated();
        case VIEWER -> rule.hasRole("VIEWER");
        case APPROVER -> rule.hasRole("APPROVER");
        case ADMIN -> rule.hasRole("ADMIN");
        case AGENT -> rule.denyAll();
        default -> rule.denyAll();
      }
    }
    authorize.anyRequest().denyAll();
  }
}
