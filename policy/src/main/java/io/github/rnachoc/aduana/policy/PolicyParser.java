// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Ignacio Rodríguez Caro
package io.github.rnachoc.aduana.policy;

import com.networknt.schema.Schema;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SpecificationVersion;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import tools.jackson.core.JacksonException;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;
import tools.jackson.dataformat.yaml.YAMLMapper;

/**
 * Turns the text of a policy and its test files into a {@link PolicyBundle}, or into the list of
 * every problem that stops it from loading.
 *
 * <p>Works on strings only: reading files is the caller's job, so this module has no I/O.
 *
 * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
 */
public final class PolicyParser {

  private static final YAMLMapper YAML =
      YAMLMapper.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build();
  private static final JsonMapper JSON = JsonMapper.builder().build();
  private static final Schema POLICY_SCHEMA = schema("policy.schema.json");
  private static final Schema TESTS_SCHEMA = schema("policy-tests.schema.json");
  private static final Set<String> COMPARATORS =
      Set.of(
          "eq", "ne", "in", "not_in", "gt", "gte", "lt", "lte", "matches", "exists", "length_lte");

  /**
   * The outcome of parsing.
   *
   * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
   */
  public sealed interface Result {

    /**
     * The bundle is valid.
     *
     * @param bundle the parsed bundle
     * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
     */
    record Valid(PolicyBundle bundle) implements Result {}

    /**
     * The bundle cannot load.
     *
     * @param errors every problem found, each naming its file and location
     * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
     */
    record Invalid(List<String> errors) implements Result {}
  }

  /**
   * Prevents instances: the parser is a function.
   *
   * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
   */
  private PolicyParser() {}

  /**
   * Parses a policy and its test files.
   *
   * @param policyYaml the text of `policy.yaml`
   * @param testFiles test file name to its text
   * @return the bundle, or every error
   * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
   */
  public static Result parse(String policyYaml, Map<String, String> testFiles) {
    List<String> errors = new ArrayList<>();
    Optional<JsonNode> policy = read("policy.yaml", policyYaml, POLICY_SCHEMA, errors);
    Map<String, JsonNode> tests = new TreeMap<>();
    testFiles.forEach((name, text) ->
        read(name, text, TESTS_SCHEMA, errors).ifPresent(tree -> tests.put(name, tree)));
    if (policy.isEmpty() || !errors.isEmpty()) {
      return new Result.Invalid(errors);
    }
    List<Rule> rules = rules(policy.get(), errors);
    List<PolicyTestCase> cases = new ArrayList<>();
    tests.forEach((name, tree) -> tree.forEach(node -> cases.add(testCase(name, node))));
    if (!errors.isEmpty()) {
      return new Result.Invalid(errors);
    }
    ObjectNode identity = JSON.createObjectNode();
    identity.set("policy", policy.get());
    ObjectNode testsNode = identity.putObject("tests");
    tests.forEach(testsNode::set);
    return new Result.Valid(new PolicyBundle(rules, cases, Canonical.sha256(identity)));
  }

  /**
   * Parses YAML and validates it against a schema.
   *
   * @param name the file name, for messages
   * @param text the YAML
   * @param schema the schema it must satisfy
   * @param errors collects problems
   * @return the tree, if it parsed and validated
   * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
   */
  private static Optional<JsonNode> read(
      String name, String text, Schema schema, List<String> errors) {
    JsonNode tree;
    try {
      tree = YAML.readTree(text);
    } catch (JacksonException exception) {
      errors.add(name + ": not valid YAML: " + exception.getOriginalMessage());
      return Optional.empty();
    }
    if (tree == null || tree.isMissingNode()) {
      errors.add(name + ": empty document");
      return Optional.empty();
    }
    List<String> found =
        schema.validate(tree).stream()
            .map(error -> name + ": " + error.getInstanceLocation() + " " + error.getMessage())
            .toList();
    errors.addAll(found);
    return found.isEmpty() ? Optional.of(tree) : Optional.empty();
  }

  /**
   * Builds the rules and checks what the schema cannot: unique ids, one comparator per condition,
   * valid paths and patterns.
   *
   * @param policy the validated policy tree
   * @param errors collects problems
   * @return the rules
   * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
   */
  private static List<Rule> rules(JsonNode policy, List<String> errors) {
    List<Rule> rules = new ArrayList<>();
    Set<String> ids = new HashSet<>();
    for (JsonNode node : policy.path("rules")) {
      String id = node.path("id").asString();
      if (!ids.add(id)) {
        errors.add("policy.yaml: rule id " + id + " is used twice");
        continue;
      }
      try {
        rules.add(rule(node));
      } catch (IllegalArgumentException exception) {
        errors.add("policy.yaml: rule " + id + ": " + exception.getMessage());
      }
    }
    return rules;
  }

  /**
   * Builds one rule.
   *
   * @param node the rule tree
   * @return the rule
   * @throws IllegalArgumentException for a condition the schema accepts but the model refuses
   * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
   */
  private static Rule rule(JsonNode node) {
    JsonNode match = node.path("match");
    List<ArgCondition> conditions = new ArrayList<>();
    for (JsonNode condition : match.path("args")) {
      conditions.add(condition(condition));
    }
    Rule.Match parsed =
        new Rule.Match(
            principal(match.path("principal")),
            tool(match.path("tool")),
            conditions,
            context(match.path("context")));
    return new Rule(
        node.path("id").asString(),
        node.path("description").asString(""),
        parsed,
        Effect.fromYaml(node.path("effect").asString()),
        obligations(node.path("obligations")),
        approval(node.path("approval")),
        Optional.ofNullable(node.get("reason_code")).map(JsonNode::asString));
  }

  /**
   * Builds a caller condition.
   *
   * @param node the `principal` tree, possibly missing
   * @return the condition
   * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
   */
  private static Rule.PrincipalMatch principal(JsonNode node) {
    Map<String, StringMatcher> attributes = new LinkedHashMap<>();
    node.path("attributes")
        .properties()
        .forEach(e -> attributes.put(e.getKey(), matcher(e.getValue())));
    List<String> groups = new ArrayList<>();
    node.path("groups").path("any_of").forEach(group -> groups.add(group.asString()));
    return new Rule.PrincipalMatch(optionalMatcher(node.path("name")), attributes, groups);
  }

  /**
   * Builds a tool condition.
   *
   * @param node the `tool` tree, possibly missing
   * @return the condition
   * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
   */
  private static Rule.ToolMatch tool(JsonNode node) {
    return new Rule.ToolMatch(
        optionalMatcher(node.path("server")),
        optionalMatcher(node.path("name")),
        optionalBoolean(node.path("side_effect")),
        optionalBoolean(node.path("untrusted_source")),
        optionalMatcher(node.path("sensitivity")));
  }

  /**
   * Builds a mark condition.
   *
   * @param node the `context` tree, possibly missing
   * @return the condition
   * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
   */
  private static Rule.ContextMatch context(JsonNode node) {
    return new Rule.ContextMatch(
        optionalBoolean(node.path("flow_from_untrusted")),
        optionalBoolean(node.path("context_tainted")));
  }

  /**
   * Builds an argument condition with exactly one comparator.
   *
   * @param node the condition tree
   * @return the condition
   * @throws IllegalArgumentException if it has no comparator or more than one, or a bad pattern
   * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
   */
  private static ArgCondition condition(JsonNode node) {
    List<String> used = node.propertyNames().stream().filter(COMPARATORS::contains).toList();
    if (used.size() != 1) {
      throw new IllegalArgumentException(
          "condition on "
              + node.path("path").asString()
              + " needs exactly one comparator, has "
              + used);
    }
    String name = used.getFirst();
    JsonNode operand = node.get(name);
    Optional<BoundedRegex> regex =
        "matches".equals(name)
            ? Optional.of(new BoundedRegex(operand.asString()))
            : Optional.empty();
    return new ArgCondition(
        JsonPath.parse(node.path("path").asString()),
        ArgCondition.Comparator.valueOf(name.toUpperCase(Locale.ROOT)),
        operand,
        regex,
        node.path("any").asBoolean(false));
  }

  /**
   * Builds the obligations of a rule.
   *
   * @param node the `obligations` tree, possibly missing
   * @return the obligations
   * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
   */
  private static Rule.Obligations obligations(JsonNode node) {
    return new Rule.Obligations(
        strings(node.path("redact_result")),
        strings(node.path("deny_args_containing")),
        strings(node.path("flag_args_containing")));
  }

  /**
   * Builds the approval settings, with defaults when the rule names none.
   *
   * @param node the `approval` tree, possibly missing
   * @return the settings
   * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
   */
  private static Rule.ApprovalSpec approval(JsonNode node) {
    if (node.isMissingNode()) {
      return Rule.ApprovalSpec.DEFAULT;
    }
    Duration expires =
        node.has("expires_in")
            ? duration(node.get("expires_in").asString())
            : Rule.ApprovalSpec.DEFAULT.expiresIn();
    return new Rule.ApprovalSpec(node.path("approvers_group").asString(), expires);
  }

  /**
   * Parses `15m`, `30s` or `2h`.
   *
   * @param text the duration as written
   * @return the duration
   * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
   */
  static Duration duration(String text) {
    long amount = Long.parseLong(text.substring(0, text.length() - 1));
    return switch (text.charAt(text.length() - 1)) {
      case 's' -> Duration.ofSeconds(amount);
      case 'm' -> Duration.ofMinutes(amount);
      default -> Duration.ofHours(amount);
    };
  }

  /**
   * Builds one test case, with neutral defaults for what it does not say.
   *
   * @param file the test file
   * @param node the case tree
   * @return the case
   * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
   */
  private static PolicyTestCase testCase(String file, JsonNode node) {
    JsonNode given = node.path("given");
    JsonNode principal = given.path("principal");
    Map<String, String> attributes = new LinkedHashMap<>();
    principal
        .path("attributes")
        .properties()
        .forEach(e -> attributes.put(e.getKey(), e.getValue().asString()));
    JsonNode tool = given.path("tool");
    JsonNode expect = node.path("expect");
    CallContext context =
        new CallContext(
            new CallContext.PrincipalFacts(
                principal.path("name").asString("test-principal"),
                attributes,
                strings(principal.path("groups"))),
            new CallContext.ToolFacts(
                tool.path("server").asString(),
                tool.path("name").asString(),
                tool.path("side_effect").asBoolean(false),
                tool.path("untrusted_source").asBoolean(false),
                tool.path("sensitivity").asString("low")),
            given.has("args") ? given.get("args") : JSON.createObjectNode(),
            new CallContext.ContextFacts(
                given.path("context").path("flow_from_untrusted").asBoolean(false),
                given.path("context").path("context_tainted").asBoolean(false)));
    Optional<List<String>> rules =
        expect.has("rules")
            ? Optional.of(List.copyOf(strings(expect.get("rules"))))
            : Optional.empty();
    return new PolicyTestCase(
        file,
        node.path("name").asString(),
        context,
        Effect.fromYaml(expect.path("decision").asString()),
        rules,
        Optional.ofNullable(expect.get("reason_code")).map(JsonNode::asString),
        expect.has("redact_result")
            ? Optional.of(strings(expect.get("redact_result")))
            : Optional.empty());
  }

  /**
   * Reads a string matcher.
   *
   * @param node a string or an `in` / `not_in` object
   * @return the matcher
   * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
   */
  private static StringMatcher matcher(JsonNode node) {
    if (node.isString()) {
      return StringMatcher.exact(node.asString());
    }
    boolean exclude = node.has("not_in");
    List<String> values = List.copyOf(strings(node.path(exclude ? "not_in" : "in")));
    return new StringMatcher(
        exclude ? StringMatcher.Operator.NOT_IN : StringMatcher.Operator.IN, values);
  }

  /**
   * Reads a matcher that may be absent.
   *
   * @param node the node, possibly missing
   * @return the matcher, if present
   * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
   */
  private static Optional<StringMatcher> optionalMatcher(JsonNode node) {
    return node.isMissingNode() ? Optional.empty() : Optional.of(matcher(node));
  }

  /**
   * Reads a boolean that may be absent.
   *
   * @param node the node, possibly missing
   * @return the value, if present
   * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
   */
  private static Optional<Boolean> optionalBoolean(JsonNode node) {
    return node.isBoolean() ? Optional.of(node.asBoolean()) : Optional.empty();
  }

  /**
   * Reads an array of strings in order, without duplicates.
   *
   * @param node the array, possibly missing
   * @return the strings
   * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
   */
  private static Set<String> strings(JsonNode node) {
    Set<String> values = new LinkedHashSet<>();
    node.forEach(value -> values.add(value.asString()));
    return values;
  }

  /**
   * Loads a schema shipped in this module.
   *
   * @param file the schema file name
   * @return the compiled schema
   * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
   */
  private static Schema schema(String file) {
    try (InputStream input = PolicyParser.class.getResourceAsStream("schema/" + file)) {
      if (input == null) {
        throw new IllegalStateException("Missing schema " + file);
      }
      return SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12)
          .getSchema(JSON.readTree(input));
    } catch (IOException exception) {
      throw new UncheckedIOException(exception);
    }
  }
}
