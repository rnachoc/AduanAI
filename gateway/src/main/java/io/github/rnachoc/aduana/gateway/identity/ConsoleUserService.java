// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Ignacio Rodríguez Caro
package io.github.rnachoc.aduana.gateway.identity;

import java.time.Clock;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Creates console users and checks their passwords with Argon2id.
 *
 * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
 */
@Service
public class ConsoleUserService {

  private static final int MINIMUM_PASSWORD_LENGTH = 10;

  private final ConsoleUserRepository users;
  private final PrincipalService principals;
  private final Argon2PasswordEncoder encoder =
      Argon2PasswordEncoder.defaultsForSpringSecurity_v5_8();
  private final String unknownUserHash;
  private final Clock clock;

  /**
   * Creates the service.
   *
   * @param users console user storage
   * @param principals creates the user's principal
   * @param clock the clock
   * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
   */
  public ConsoleUserService(ConsoleUserRepository users, PrincipalService principals, Clock clock) {
    this.users = users;
    this.principals = principals;
    this.clock = clock;
    this.unknownUserHash = encoder.encode("no user has this password " + UUID.randomUUID());
  }

  /**
   * Creates a console user and its principal.
   *
   * @param tenantId the tenant
   * @param email the sign-in address
   * @param password the password, at least 10 characters
   * @param role the role
   * @return the new user
   * @throws IllegalArgumentException if the password is too short or the address is taken
   * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
   */
  @Transactional
  public ConsoleUser create(UUID tenantId, String email, String password, ConsoleUser.Role role) {
    if (password.length() < MINIMUM_PASSWORD_LENGTH) {
      throw new IllegalArgumentException("Passwords need at least 10 characters");
    }
    if (users.findByEmail(email).isPresent()) {
      throw new IllegalArgumentException("A console user with that e-mail exists");
    }
    String name = "user." + email.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9._-]", "-");
    Principal principal =
        principals.create(
            tenantId,
            Principal.Kind.USER,
            name,
            Map.of(),
            List.of(role.name().toLowerCase(Locale.ROOT)));
    ConsoleUser user = new ConsoleUser(tenantId, UUID.randomUUID(), principal.id(), email, role);
    users.insert(user, encoder.encode(password), clock.instant());
    return user;
  }

  /**
   * Checks an e-mail and password.
   *
   * <p>An unknown address still costs one Argon2 verification, so response time does not reveal
   * which addresses exist.
   *
   * @param email the sign-in address
   * @param password the password
   * @return the user when both match
   * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
   */
  public Optional<ConsoleUser> authenticate(String email, String password) {
    Optional<ConsoleUserRepository.StoredUser> stored = users.findByEmail(email);
    String hash =
        stored.map(ConsoleUserRepository.StoredUser::passwordHash).orElse(unknownUserHash);
    boolean matches = encoder.matches(password, hash);
    return stored.filter(user -> matches).map(ConsoleUserRepository.StoredUser::user);
  }

  /**
   * Tells whether any console user exists.
   *
   * @return true once the first user was created
   * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
   */
  public boolean anyUserExists() {
    return users.count() > 0;
  }
}
