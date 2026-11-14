// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Ignacio Rodríguez Caro
package io.github.rnachoc.aduana.gateway.audit;

/**
 * A summary of an entry just appended, published for the console's live activity view.
 *
 * @param seq the entry's seq
 * @param ts its time
 * @param type its type
 * @param tool the tool, if any
 * @param decision the decision, if any
 * @param reasonCode the reason code, if any
 * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
 */
public record AuditAppended(
    long seq, String ts, String type, String tool, String decision, String reasonCode) {}
