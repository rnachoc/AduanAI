// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Ignacio Rodríguez Caro
package io.github.rnachoc.aduana.gateway.identity;

import java.time.Instant;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Reads and writes console users.
 *
 * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
 */
@Repository
public class ConsoleUserRepository {

  private final JdbcClient jdbc;

  /**
   * A console user with the stored password hash, for sign-in only.
   *
   * @param user the user
   * @param passwordHash the Argon2id hash
   * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
   */
  public record StoredUser(ConsoleUser user, String passwordHash) {}

  /**
   * Creates the repository.
   *
   * @param jdbc the JDBC client
   * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
   */
  public ConsoleUserRepository(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  /**
   * Inserts a console user.
   *
   * @param user the user
   * @param passwordHash the Argon2id hash of the password
   * @param now the creation time
   * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
   */
  public void insert(ConsoleUser user, String passwordHash, Instant now) {
    jdbc.sql(
            """
            insert into console_user (tenant_id, id, principal_id, email, password_hash, role, created_at)
            values (:tenant, :id, :principal, :email, :hash, :role, :now)
            """)
        .param("tenant", user.tenantId())
        .param("id", user.id())
        .param("principal", user.principalId())
        .param("email", user.email())
        .param("hash", passwordHash)
        .param("role", user.role().name().toLowerCase(Locale.ROOT))
        .param("now", PrincipalRepository.utc(now))
        .update();
  }

  /**
   * Finds a user by e-mail, ignoring case.
   *
   * @param email the sign-in address
   * @return the user and hash, if any
   * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
   */
  public Optional<StoredUser> findByEmail(String email) {
    return jdbc.sql("select * from console_user where lower(email) = lower(:email)")
        .param("email", email)
        .query((rs, row) ->
            new StoredUser(
                new ConsoleUser(
                    rs.getObject("tenant_id", UUID.class),
                    rs.getObject("id", UUID.class),
                    rs.getObject("principal_id", UUID.class),
                    rs.getString("email"),
                    ConsoleUser.Role.valueOf(rs.getString("role").toUpperCase(Locale.ROOT))),
                rs.getString("password_hash")))
        .optional();
  }

  /**
   * Counts console users, to know whether the first admin must be created.
   *
   * @return the number of users across tenants
   * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
   */
  public long count() {
    return jdbc.sql("select count(*) from console_user").query(Long.class).single();
  }
}
