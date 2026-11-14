// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Ignacio Rodríguez Caro
package io.github.rnachoc.aduana.gateway.web.api;

import io.github.rnachoc.aduana.gateway.identity.ApiKey;
import io.github.rnachoc.aduana.gateway.identity.ConsoleUser;
import io.github.rnachoc.aduana.gateway.identity.ConsoleUserService;
import io.github.rnachoc.aduana.gateway.identity.Principal;
import io.github.rnachoc.aduana.gateway.identity.PrincipalService;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Administers principals and their API keys.
 *
 * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
 */
@RestController
@RequestMapping("/api/v1")
public class IdentityController {

  private final PrincipalService principals;
  private final ConsoleUserService users;

  /**
   * A new principal.
   *
   * @param kind agent or user; agent when absent
   * @param name the unique name
   * @param attributes attributes policies match on
   * @param groups groups
   * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
   */
  public record PrincipalBody(
      String kind, String name, Map<String, String> attributes, List<String> groups) {}

  /**
   * A principal as the API shows it.
   *
   * @param id the id
   * @param kind agent or user
   * @param name the name
   * @param attributes the attributes
   * @param groups the groups
   * @param status active or disabled
   * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
   */
  public record PrincipalView(
      UUID id,
      String kind,
      String name,
      Map<String, String> attributes,
      List<String> groups,
      String status) {}

  /**
   * A key request.
   *
   * @param validDays how many days the key works; no expiry when absent
   * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
   */
  public record KeyBody(Integer validDays) {}

  /**
   * A key as the API shows it; `secret` is present only in the creation response.
   *
   * @param id the id
   * @param prefix the visible prefix
   * @param createdAt when created
   * @param expiresAt when it expires, if ever
   * @param revokedAt when revoked, if it was
   * @param secret the full key, once
   * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
   */
  public record KeyView(
      UUID id,
      String prefix,
      Instant createdAt,
      Instant expiresAt,
      Instant revokedAt,
      String secret) {}

  /**
   * Creates the controller.
   *
   * @param principals the principal service
   * @param users creates console users
   * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
   */
  public IdentityController(PrincipalService principals, ConsoleUserService users) {
    this.principals = principals;
    this.users = users;
  }

  /**
   * Lists principals.
   *
   * @param user the signed-in administrator
   * @return the principals
   * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
   */
  @GetMapping("/principals")
  public List<PrincipalView> list(@AuthenticationPrincipal ConsoleUser user) {
    return principals.list(user.tenantId()).stream().map(IdentityController::view).toList();
  }

  /**
   * Creates a principal.
   *
   * @param user the signed-in administrator
   * @param body the principal
   * @return the new principal
   * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
   */
  @PostMapping("/principals")
  @ResponseStatus(HttpStatus.CREATED)
  public PrincipalView create(
      @AuthenticationPrincipal ConsoleUser user, @RequestBody PrincipalBody body) {
    Principal.Kind kind =
        Principal.Kind.valueOf(
            Optional.ofNullable(body.kind()).orElse("agent").toUpperCase(Locale.ROOT));
    return view(
        principals.create(
            user.tenantId(),
            kind,
            Optional.ofNullable(body.name()).orElse(""),
            Optional.ofNullable(body.attributes()).orElse(Map.of()),
            Optional.ofNullable(body.groups()).orElse(List.of())));
  }

  /**
   * A new console user.
   *
   * @param email the sign-in address
   * @param password the password, at least 10 characters
   * @param role admin, approver or viewer
   * @param groups approver groups such as finance; the role's name when absent
   * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
   */
  public record UserBody(String email, String password, String role, List<String> groups) {}

  /**
   * Creates a console user.
   *
   * @param user the signed-in administrator
   * @param body the new user
   * @return the user's address and role
   * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
   */
  @PostMapping("/users")
  @ResponseStatus(HttpStatus.CREATED)
  public Map<String, String> createUser(
      @AuthenticationPrincipal ConsoleUser user, @RequestBody UserBody body) {
    ConsoleUser.Role role =
        ConsoleUser.Role.valueOf(
            Optional.ofNullable(body.role()).orElse("viewer").toUpperCase(Locale.ROOT));
    List<String> groups =
        Optional.ofNullable(body.groups()).orElse(List.of(role.name().toLowerCase(Locale.ROOT)));
    ConsoleUser created =
        users.create(
            user.tenantId(),
            Optional.ofNullable(body.email()).orElse(""),
            Optional.ofNullable(body.password()).orElse(""),
            role,
            groups);
    return Map.of("email", created.email(), "role", created.role().name().toLowerCase(Locale.ROOT));
  }

  /**
   * Lists a principal's keys without secrets.
   *
   * @param user the signed-in administrator
   * @param id the principal
   * @return the keys
   * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
   */
  @GetMapping("/principals/{id}/keys")
  public List<KeyView> keys(@AuthenticationPrincipal ConsoleUser user, @PathVariable UUID id) {
    return principals.keys(user.tenantId(), id).stream().map(key -> view(key, null)).toList();
  }

  /**
   * Issues a key; the response is the only time the secret is shown.
   *
   * @param user the signed-in administrator
   * @param id the principal
   * @param body the key request
   * @return the key with its secret
   * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
   */
  @PostMapping("/principals/{id}/keys")
  @ResponseStatus(HttpStatus.CREATED)
  public KeyView issue(
      @AuthenticationPrincipal ConsoleUser user, @PathVariable UUID id, @RequestBody KeyBody body) {
    Optional<Duration> validity = Optional.ofNullable(body.validDays()).map(Duration::ofDays);
    PrincipalService.IssuedKey issued =
        principals.issueKey(user.tenantId(), id, validity, Optional.of(user.principalId()));
    return view(issued.key(), issued.secret());
  }

  /**
   * Revokes a key.
   *
   * @param user the signed-in administrator
   * @param id the key
   * @return 204, or 404 if there was no active key with that id
   * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
   */
  @DeleteMapping("/keys/{id}")
  public ResponseEntity<Void> revoke(
      @AuthenticationPrincipal ConsoleUser user, @PathVariable UUID id) {
    boolean revoked = principals.revokeKey(user.tenantId(), id, Optional.of(user.principalId()));
    return revoked ? ResponseEntity.noContent().build() : ResponseEntity.notFound().build();
  }

  /**
   * Shows a principal.
   *
   * @param principal the principal
   * @return the view
   * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
   */
  private static PrincipalView view(Principal principal) {
    return new PrincipalView(
        principal.id(),
        principal.kind().name().toLowerCase(Locale.ROOT),
        principal.name(),
        principal.attributes(),
        principal.groups(),
        principal.status());
  }

  /**
   * Shows a key.
   *
   * @param key the key
   * @param secret the secret, only right after creation
   * @return the view
   * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
   */
  private static KeyView view(ApiKey key, String secret) {
    return new KeyView(
        key.id(),
        key.prefix(),
        key.createdAt(),
        key.expiresAt().orElse(null),
        key.revokedAt().orElse(null),
        secret);
  }
}
