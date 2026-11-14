// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Ignacio Rodríguez Caro
package io.github.rnachoc.aduana.gateway.web.api;

import io.github.rnachoc.aduana.gateway.approval.ApprovalService;
import java.net.URI;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Answers API errors as RFC 9457 problem documents with a stable `code`.
 *
 * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
 */
@RestControllerAdvice(basePackages = "io.github.rnachoc.aduana.gateway.web.api")
public class ApiExceptionHandler {

  /**
   * Turns a rejected request into 400 or 404.
   *
   * @param exception what was wrong with the request
   * @return the problem
   * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
   */
  @ExceptionHandler(IllegalArgumentException.class)
  public ProblemDetail invalid(IllegalArgumentException exception) {
    if ("The approval is no longer pending".equals(exception.getMessage())) {
      ProblemDetail conflict =
          ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, exception.getMessage());
      conflict.setProperty("code", "APPROVAL_NOT_PENDING");
      return conflict;
    }
    boolean missing =
        exception.getMessage() != null && exception.getMessage().startsWith("No such");
    HttpStatus status = missing ? HttpStatus.NOT_FOUND : HttpStatus.BAD_REQUEST;
    ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, exception.getMessage());
    problem.setType(URI.create("about:blank"));
    problem.setProperty("code", missing ? "NOT_FOUND" : "INVALID_REQUEST");
    return problem;
  }

  /**
   * Refuses a decision by someone outside the approval's group.
   *
   * @param exception who may decide
   * @return a 403 problem
   * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
   */
  @ExceptionHandler(ApprovalService.NotAnApprover.class)
  public ProblemDetail forbidden(ApprovalService.NotAnApprover exception) {
    ProblemDetail problem =
        ProblemDetail.forStatusAndDetail(HttpStatus.FORBIDDEN, exception.getMessage());
    problem.setType(URI.create("about:blank"));
    problem.setProperty("code", "NOT_AN_APPROVER");
    return problem;
  }
}
