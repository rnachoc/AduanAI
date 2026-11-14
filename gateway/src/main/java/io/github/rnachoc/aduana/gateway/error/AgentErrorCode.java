// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Ignacio Rodríguez Caro
package io.github.rnachoc.aduana.gateway.error;

/**
 * The closed list of error codes an agent can receive (plan §6.3), each with a hint for the model.
 *
 * <p>Hints say what to do next. They never quote a policy rule or name another tool, so an error
 * teaches an agent nothing about what else exists.
 *
 * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
 */
public enum AgentErrorCode {
  /** The tool does not exist or this principal may not see it; the two are indistinguishable. */
  TOOL_NOT_FOUND("Unknown tool.", "List the available tools and use one of them."),
  /** The arguments do not satisfy the tool's input schema or a size limit. */
  ARGS_INVALID("The arguments are not valid for this tool.", "Fix the arguments and try again."),
  /** A policy rule denies the call. */
  POLICY_DENIED(
      "The call is not allowed.", "Do not retry. Tell the user this action is not permitted."),
  /** The call waits for a person to approve it. */
  APPROVAL_PENDING(
      "The call needs approval.",
      "Tell the user approval was requested, then retry the same call with the same arguments later."),
  /** A person rejected the call. */
  APPROVAL_REJECTED("The call was rejected by an approver.", "Do not retry. Tell the user."),
  /** Too many calls in the current window. */
  RATE_LIMITED("Too many calls.", "Wait before retrying."),
  /** The principal's budget for the period is spent. */
  BUDGET_EXCEEDED("The budget for this period is spent.", "Do not retry until the next period."),
  /** The idempotency key was already used with different arguments. */
  IDEMPOTENCY_CONFLICT(
      "The idempotency key was used with different arguments.",
      "Use a new idempotency key for a different call."),
  /** The upstream server did not answer in time. */
  UPSTREAM_TIMEOUT("The tool did not answer in time.", "Retry later."),
  /** The upstream server could not be reached or answered with an error. */
  UPSTREAM_ERROR("The tool is unavailable.", "Retry later."),
  /** The audit log could not record the call, so it was not forwarded. */
  AUDIT_UNAVAILABLE("The call could not be recorded and was not run.", "Retry later.");

  private final String message;
  private final String hint;

  /**
   * Binds a code to its fixed message and hint.
   *
   * @param message what happened, in one sentence
   * @param hint what the agent should do next
   * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
   */
  AgentErrorCode(String message, String hint) {
    this.message = message;
    this.hint = hint;
  }

  /**
   * Returns the fixed message.
   *
   * @return the message
   * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
   */
  public String message() {
    return message;
  }

  /**
   * Returns the fixed hint.
   *
   * @return the hint
   * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
   */
  public String hint() {
    return hint;
  }
}
