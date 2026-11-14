// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Ignacio Rodríguez Caro
package io.github.rnachoc.aduana.gateway.web.api;

import io.github.rnachoc.aduana.gateway.identity.ConsoleUser;
import io.github.rnachoc.aduana.gateway.identity.ConsoleUserService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Signs console users in and out with a session cookie.
 *
 * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
 */
@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

  private final ConsoleUserService users;
  private final HttpSessionSecurityContextRepository sessions;

  /**
   * The sign-in form.
   *
   * @param email the address
   * @param password the password
   * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
   */
  public record LoginRequest(String email, String password) {}

  /**
   * Who is signed in.
   *
   * @param email the address
   * @param role the role, lowercase
   * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
   */
  public record Me(String email, String role) {}

  /**
   * Creates the controller.
   *
   * @param users checks passwords
   * @param sessions stores the signed-in user in the session
   * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
   */
  public AuthController(ConsoleUserService users, HttpSessionSecurityContextRepository sessions) {
    this.users = users;
    this.sessions = sessions;
  }

  /**
   * Signs a user in; a new session id is issued so a planted session cannot be reused.
   *
   * @param login the form
   * @param request the request
   * @param response the response
   * @return the user, or 401 with no hint about which part was wrong
   * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
   */
  @PostMapping("/login")
  public ResponseEntity<Me> login(
      @RequestBody LoginRequest login, HttpServletRequest request, HttpServletResponse response) {
    Optional<ConsoleUser> user =
        users.authenticate(
            Optional.ofNullable(login.email()).orElse(""),
            Optional.ofNullable(login.password()).orElse(""));
    if (user.isEmpty()) {
      return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
    }
    request.getSession(true);
    request.changeSessionId();
    SecurityContext context = SecurityContextHolder.createEmptyContext();
    context.setAuthentication(authentication(user.get()));
    SecurityContextHolder.setContext(context);
    sessions.saveContext(context, request, response);
    return ResponseEntity.ok(me(user.get()));
  }

  /**
   * Ends the session.
   *
   * @param request the request
   * @return 204
   * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
   */
  @PostMapping("/logout")
  public ResponseEntity<Void> logout(HttpServletRequest request) {
    Optional.ofNullable(request.getSession(false)).ifPresent(session -> session.invalidate());
    SecurityContextHolder.clearContext();
    return ResponseEntity.noContent().build();
  }

  /**
   * Returns the signed-in user and makes sure the CSRF cookie is set for the next write.
   *
   * @param authentication the current authentication
   * @param csrf the CSRF token, read so the cookie is issued
   * @return the user
   * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
   */
  @GetMapping("/me")
  public Me me(Authentication authentication, CsrfToken csrf) {
    csrf.getToken();
    return me((ConsoleUser) authentication.getPrincipal());
  }

  /**
   * Builds the authentication with one authority per role the user's role includes.
   *
   * @param user the console user
   * @return the authentication
   * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
   */
  private static Authentication authentication(ConsoleUser user) {
    List<SimpleGrantedAuthority> authorities =
        Arrays.stream(ConsoleUser.Role.values())
            .filter(role -> user.role().includes(role))
            .map(role -> new SimpleGrantedAuthority("ROLE_" + role.name()))
            .toList();
    return UsernamePasswordAuthenticationToken.authenticated(user, null, authorities);
  }

  /**
   * Describes a user for the console.
   *
   * @param user the user
   * @return the description
   * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
   */
  private static Me me(ConsoleUser user) {
    return new Me(user.email(), user.role().name().toLowerCase(Locale.ROOT));
  }
}
