// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Ignacio Rodríguez Caro
package io.github.rnachoc.aduana.cli.attacks;

import java.util.List;

/**
 * How a case ended.
 *
 * @param attack the case
 * @param stopped true when every expectation held
 * @param observed what each checked step saw, in order
 * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
 */
public record CaseResult(AttackCase attack, boolean stopped, List<String> observed) {}
