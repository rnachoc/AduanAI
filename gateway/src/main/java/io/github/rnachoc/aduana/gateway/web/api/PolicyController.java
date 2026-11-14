// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Ignacio Rodríguez Caro
package io.github.rnachoc.aduana.gateway.web.api;

import io.github.rnachoc.aduana.gateway.identity.ConsoleUser;
import io.github.rnachoc.aduana.gateway.policy.PolicyRepository;
import io.github.rnachoc.aduana.gateway.policy.PolicyService;
import io.github.rnachoc.aduana.gateway.policy.PolicySource;
import io.github.rnachoc.aduana.policy.PolicyBundle;
import io.github.rnachoc.aduana.policy.PolicyTestRunner;
import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Shows the active policy and loads a new one; a bundle is activated only after it validates, its
 * tests pass and its conditions fit the catalog's schemas.
 *
 * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
 */
@RestController
@RequestMapping("/api/v1/policy")
public class PolicyController {

  private final PolicyService policies;

  /**
   * A bundle to load.
   *
   * @param policy the text of `policy.yaml`
   * @param tests test file name to text
   * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
   */
  public record PolicyBody(String policy, Map<String, String> tests) {}

  /**
   * The active bundle.
   *
   * @param sha256 its identity
   * @param loadedAt when it was activated
   * @param policy its text
   * @param tests its test files
   * @param results the outcome of each test case now
   * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
   */
  public record ActivePolicy(
      String sha256,
      Instant loadedAt,
      String policy,
      Map<String, String> tests,
      List<PolicyTestRunner.Outcome> results) {}

  /**
   * A successful load.
   *
   * @param sha256 the bundle identity
   * @param rules how many rules
   * @param tests how many test cases passed
   * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
   */
  public record Loaded(String sha256, int rules, int tests) {}

  /**
   * Creates the controller.
   *
   * @param policies the policy service
   * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
   */
  public PolicyController(PolicyService policies) {
    this.policies = policies;
  }

  /**
   * Returns the active bundle and its test results.
   *
   * @param user the signed-in administrator
   * @return the bundle, or 404 if none was ever loaded
   * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
   */
  @GetMapping
  public ResponseEntity<ActivePolicy> active(@AuthenticationPrincipal ConsoleUser user) {
    Optional<PolicyRepository.Stored> stored = policies.activeSource(user.tenantId());
    Optional<PolicyBundle> bundle = policies.activeBundle(user.tenantId());
    if (stored.isEmpty() || bundle.isEmpty()) {
      return ResponseEntity.notFound().build();
    }
    return ResponseEntity.ok(
        new ActivePolicy(
            stored.get().sha256(),
            stored.get().loadedAt(),
            stored.get().source().policy(),
            stored.get().source().tests(),
            PolicyTestRunner.run(bundle.get())));
  }

  /**
   * Loads a bundle.
   *
   * @param user the signed-in administrator
   * @param body the bundle texts
   * @return 201 with the new identity, or 400 with every reason it was refused
   * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
   */
  @PostMapping
  public ResponseEntity<?> load(
      @AuthenticationPrincipal ConsoleUser user, @RequestBody PolicyBody body) {
    PolicySource source =
        new PolicySource(
            Optional.ofNullable(body.policy()).orElse(""),
            Optional.ofNullable(body.tests()).orElse(Map.of()));
    PolicyService.LoadResult result =
        policies.load(user.tenantId(), source, Optional.of(user.principalId()));
    return switch (result) {
      case PolicyService.LoadResult.Loaded(String sha256, int rules, int tests) ->
          ResponseEntity.status(HttpStatus.CREATED).body(new Loaded(sha256, rules, tests));
      case PolicyService.LoadResult.Rejected(List<String> errors) -> {
        ProblemDetail problem =
            ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, "The policy was not loaded.");
        problem.setType(URI.create("about:blank"));
        problem.setProperty("code", "POLICY_REJECTED");
        problem.setProperty("errors", errors);
        yield ResponseEntity.badRequest().body(problem);
      }
    };
  }
}
