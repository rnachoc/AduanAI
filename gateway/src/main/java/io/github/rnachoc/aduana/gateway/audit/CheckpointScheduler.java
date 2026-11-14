// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Ignacio Rodríguez Caro
package io.github.rnachoc.aduana.gateway.audit;

import io.github.rnachoc.aduana.gateway.identity.Tenants;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Signs a checkpoint every few minutes when entries were added, so a quiet log is still covered by
 * a recent signature.
 *
 * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
 */
@Component
public class CheckpointScheduler {

  private static final Logger LOG = LoggerFactory.getLogger(CheckpointScheduler.class);

  private final AuditLog audit;

  /**
   * Creates the scheduler.
   *
   * @param audit the audit log
   * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
   */
  public CheckpointScheduler(AuditLog audit) {
    this.audit = audit;
  }

  /**
   * Signs a checkpoint if one is due.
   *
   * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
   */
  @Scheduled(
      fixedDelayString = "${aduana.audit.checkpoint-interval:PT5M}",
      initialDelayString = "${aduana.audit.checkpoint-interval:PT5M}")
  public void checkpoint() {
    audit
        .checkpoint(Tenants.DEFAULT)
        .ifPresent(seq -> LOG.info("signed a checkpoint at seq {}", seq));
  }
}
