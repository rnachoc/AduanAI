// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Ignacio Rodríguez Caro
package io.github.rnachoc.aduana.gateway.limits;

import io.github.rnachoc.aduana.gateway.config.AduanaProperties;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Call rates per principal and tool, per principal, and a daily budget, counted in PostgreSQL so
 * the counters survive a restart (plan §5.8, ADR 9).
 *
 * <p>Rates use a sliding window approximated from two fixed one-minute windows: the current count
 * plus the previous window's count weighted by how much of it still overlaps the last minute.
 *
 * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
 */
@Service
public class LimitService {

  private static final Duration WINDOW = Duration.ofMinutes(1);

  private final JdbcClient jdbc;
  private final Clock clock;
  private final long perTool;
  private final long perPrincipal;
  private final long dailyBudget;
  private final TransactionTemplate transactions;

  /**
   * Why a call was refused.
   *
   * @param code `RATE_LIMITED` or `BUDGET_EXCEEDED`
   * @param retryAfter how long to wait before the call could succeed
   * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
   */
  public record Refusal(String code, Duration retryAfter) {}

  /**
   * Creates the service.
   *
   * @param jdbc the JDBC client
   * @param clock the clock
   * @param properties the configured limits
   * @param transactionManager runs each call's counting in one transaction
   * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
   */
  public LimitService(
      JdbcClient jdbc,
      Clock clock,
      AduanaProperties properties,
      PlatformTransactionManager transactionManager) {
    this.jdbc = jdbc;
    this.clock = clock;
    this.perTool = properties.limits().callsPerMinutePerTool();
    this.perPrincipal = properties.limits().callsPerMinutePerPrincipal();
    this.dailyBudget = properties.limits().dailyBudget();
    this.transactions = new TransactionTemplate(transactionManager);
  }

  /**
   * Counts a call against its limits; a refused call still counts, so hammering does not pay.
   *
   * <p>The counting runs in one transaction that does not wait for its commit to reach disk
   * (`synchronous_commit = off`): a crash can lose the last fraction of a second of counts, never a
   * log entry. ADR 9.
   *
   * @param tenantId the tenant
   * @param principalId the agent
   * @param tool the agent-facing tool name
   * @param cost the tool's budget units
   * @return the refusal, or empty when the call is within every limit
   * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
   */
  public Optional<Refusal> admit(UUID tenantId, UUID principalId, String tool, int cost) {
    return transactions.execute(status -> {
      jdbc.sql("set local synchronous_commit to off").update();
      return countCall(tenantId, principalId, tool, cost);
    });
  }

  /**
   * Counts the call in its windows and the budget.
   *
   * @param tenantId the tenant
   * @param principalId the agent
   * @param tool the tool
   * @param cost the tool's budget units
   * @return the refusal, or empty
   * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
   */
  private Optional<Refusal> countCall(UUID tenantId, UUID principalId, String tool, int cost) {
    Instant now = clock.instant();
    Optional<Refusal> refusal =
        rate(tenantId, "tool:" + principalId + ":" + tool, perTool, now)
            .or(() -> rate(tenantId, "principal:" + principalId, perPrincipal, now));
    if (refusal.isPresent() || cost <= 0) {
      return refusal;
    }
    Instant day = now.truncatedTo(ChronoUnit.DAYS);
    long spent = add(tenantId, "budget:" + principalId, day, cost);
    if (spent > dailyBudget) {
      return Optional.of(
          new Refusal("BUDGET_EXCEEDED", Duration.between(now, day.plus(Duration.ofDays(1)))));
    }
    return Optional.empty();
  }

  /**
   * Counts one call in a rate scope.
   *
   * @param tenantId the tenant
   * @param scope the counter's scope
   * @param limit calls allowed per minute
   * @param now the time
   * @return a refusal when the weighted count is over the limit
   * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
   */
  private Optional<Refusal> rate(UUID tenantId, String scope, long limit, Instant now) {
    Instant current = now.truncatedTo(ChronoUnit.MINUTES);
    long count = add(tenantId, scope, current, 1);
    long previous = count(tenantId, scope, current.minus(WINDOW));
    double overlap = 1 - (double) Duration.between(current, now).toMillis() / WINDOW.toMillis();
    double weighted = count + previous * overlap;
    if (weighted <= limit) {
      return Optional.empty();
    }
    return Optional.of(new Refusal("RATE_LIMITED", Duration.between(now, current.plus(WINDOW))));
  }

  /**
   * Adds to a counter and returns its new value, atomically.
   *
   * @param tenantId the tenant
   * @param scope the scope
   * @param window the window start
   * @param amount what to add
   * @return the counter after the addition
   * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
   */
  private long add(UUID tenantId, String scope, Instant window, long amount) {
    return jdbc.sql(
            """
            insert into rate_bucket (tenant_id, scope, window_start, count) values (:tenant, :scope, :window, :amount)
            on conflict (tenant_id, scope, window_start) do update set count = rate_bucket.count + :amount
            returning count
            """)
        .param("tenant", tenantId)
        .param("scope", scope)
        .param("window", window.atOffset(ZoneOffset.UTC))
        .param("amount", amount)
        .query(Long.class)
        .single();
  }

  /**
   * Reads a counter.
   *
   * @param tenantId the tenant
   * @param scope the scope
   * @param window the window start
   * @return the count, or 0
   * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
   */
  private long count(UUID tenantId, String scope, Instant window) {
    return jdbc.sql(
            "select coalesce(max(count), 0) from rate_bucket where tenant_id = :tenant and scope = :scope and window_start = :window")
        .param("tenant", tenantId)
        .param("scope", scope)
        .param("window", window.atOffset(ZoneOffset.UTC))
        .query(Long.class)
        .single();
  }

  /**
   * Deletes counters older than two days.
   *
   * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
   */
  @Scheduled(fixedDelayString = "${aduana.limits.cleanup-interval:PT1H}")
  public void deleteOld() {
    jdbc.sql("delete from rate_bucket where window_start < :cutoff")
        .param("cutoff", clock.instant().minus(Duration.ofDays(2)).atOffset(ZoneOffset.UTC))
        .update();
  }
}
