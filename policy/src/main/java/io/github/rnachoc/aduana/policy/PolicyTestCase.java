// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Ignacio Rodríguez Caro
package io.github.rnachoc.aduana.policy;

import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * One test case shipped next to a policy: a call and the decision it must get.
 *
 * @param file the test file it came from
 * @param name what the case checks
 * @param given the call
 * @param expectedEffect the expected effect
 * @param expectedRules the exact set of rules that must match, when given
 * @param expectedReason the expected reason code, when given
 * @param expectedRedactions the expected `redact_result` detectors, when given
 * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
 */
public record PolicyTestCase(
    String file,
    String name,
    CallContext given,
    Effect expectedEffect,
    Optional<List<String>> expectedRules,
    Optional<String> expectedReason,
    Optional<Set<String>> expectedRedactions) {}
