// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Ignacio Rodríguez Caro
package io.github.rnachoc.aduana.policy;

import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * A regular expression whose evaluation cost is bounded, so a hostile argument cannot make the
 * evaluator backtrack for minutes.
 *
 * <p>Two limits apply (ADR 5): patterns may not use back-references or look-around, and the input
 * is wrapped so that the matcher may read at most {@value #STEP_BUDGET} characters in total; past
 * that the match is abandoned and counts as "no match".
 *
 * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
 */
public final class BoundedRegex {

  /** The longest input a pattern is applied to. Longer inputs never match. */
  public static final int MAX_INPUT = 4096;

  /** How many character reads one match may perform. */
  public static final int STEP_BUDGET = 200_000;

  private static final Pattern FORBIDDEN = Pattern.compile("\\\\[1-9]|\\\\k<|\\(\\?<?[=!]");

  private final Pattern pattern;

  /**
   * Compiles a pattern after checking it uses no construct that can backtrack without bound.
   *
   * @param source the pattern
   * @throws IllegalArgumentException if the pattern is invalid or uses a forbidden construct
   * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
   */
  public BoundedRegex(String source) {
    if (FORBIDDEN.matcher(source).find()) {
      throw new IllegalArgumentException(
          "Back-references and look-around are not allowed: " + source);
    }
    try {
      this.pattern = Pattern.compile(source);
    } catch (PatternSyntaxException exception) {
      throw new IllegalArgumentException("Invalid pattern: " + source, exception);
    }
  }

  /**
   * Tells whether the pattern finds a match in the input within the budget.
   *
   * @param input the text
   * @return true only for a match found within the limits
   * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
   */
  public boolean find(String input) {
    if (input.length() > MAX_INPUT) {
      return false;
    }
    try {
      return pattern.matcher(new Budgeted(input)).find();
    } catch (BudgetExceeded exception) {
      return false;
    }
  }

  /**
   * Returns the pattern text.
   *
   * @return the source
   * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
   */
  @Override
  public String toString() {
    return pattern.pattern();
  }

  /**
   * Thrown inside a match when the read budget is spent.
   *
   * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
   */
  private static final class BudgetExceeded extends RuntimeException {
    private static final long serialVersionUID = 1L;

    /**
     * Creates the signal without a stack trace, which would cost more than the match.
     *
     * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
     */
    BudgetExceeded() {
      super("regex budget exceeded", null, false, false);
    }
  }

  /**
   * A character sequence that counts reads and stops the matcher when the budget is spent.
   *
   * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
   */
  private static final class Budgeted implements CharSequence {
    private final String text;
    private int remaining;

    /**
     * Wraps a text with a fresh budget.
     *
     * @param text the text
     * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
     */
    Budgeted(String text) {
      this.text = text;
      this.remaining = STEP_BUDGET;
    }

    /**
     * Returns one character and spends one unit of budget.
     *
     * @param index the position
     * @return the character
     * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
     */
    @Override
    public char charAt(int index) {
      if (--remaining < 0) {
        throw new BudgetExceeded();
      }
      return text.charAt(index);
    }

    /**
     * Returns the length.
     *
     * @return the length
     * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
     */
    @Override
    public int length() {
      return text.length();
    }

    /**
     * Returns a plain substring; the matcher only uses it to copy out groups, not to search.
     *
     * @param start the start
     * @param end the end
     * @return the substring
     * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
     */
    @Override
    public CharSequence subSequence(int start, int end) {
      return text.substring(start, end);
    }

    /**
     * Returns the text.
     *
     * @return the text
     * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
     */
    @Override
    public String toString() {
      return text;
    }
  }
}
