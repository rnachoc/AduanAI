// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Ignacio Rodríguez Caro
package io.github.rnachoc.aduana.gateway.inspection;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The deterministic detectors of plan §5.7. Each finds candidates with a pattern and keeps only
 * those that pass a validity check, so a DNI with a wrong control letter or a number that fails
 * Luhn is not reported.
 *
 * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
 */
public final class Detectors {

  /** Every detector id, in a fixed order. */
  public static final List<String> IDS =
      List.of("email", "phone_es", "dni_nie", "iban", "card", "secret");

  private static final String DNI_LETTERS = "TRWAGMYFPDXBNJZSQVHLCKE";

  private static final Pattern EMAIL =
      Pattern.compile(
          "(?<![\\w.+-])[A-Za-z0-9._%+-]{1,64}@(?:[A-Za-z0-9](?:[A-Za-z0-9-]{0,61}[A-Za-z0-9])?\\.)+[A-Za-z]{2,24}(?![\\w-])");
  private static final Pattern PHONE_ES =
      Pattern.compile("(?<![\\d+])(?:(?:\\+|00)34[ .-]?)?[6789]\\d{2}(?:[ .-]?\\d{3}){2}(?!\\d)");
  private static final Pattern DNI_NIE =
      Pattern.compile("(?<![A-Za-z0-9])[XYZxyz]?\\d{7,8}[-]?[A-Za-z](?![A-Za-z0-9])");
  private static final Pattern IBAN =
      Pattern.compile("(?<![A-Za-z0-9])[A-Z]{2}\\d{2}(?: ?[A-Z0-9]){11,30}(?![A-Za-z0-9])");
  private static final Pattern CARD =
      Pattern.compile("(?<![\\d-])(?:\\d[ -]?){12,18}\\d(?![\\d-])");
  private static final List<Pattern> SECRETS =
      List.of(
          Pattern.compile("(?<![A-Za-z0-9])(?:AKIA|ASIA)[0-9A-Z]{16}(?![A-Za-z0-9])"),
          Pattern.compile("(?<![A-Za-z0-9_])gh[pousr]_[A-Za-z0-9]{36}(?![A-Za-z0-9])"),
          Pattern.compile("(?<![A-Za-z0-9_])github_pat_[A-Za-z0-9_]{60,90}"),
          Pattern.compile("(?<![A-Za-z0-9])xox[abprs]-[A-Za-z0-9-]{10,72}"),
          Pattern.compile("(?<![A-Za-z0-9])(?:sk|rk)_live_[A-Za-z0-9]{20,99}"),
          Pattern.compile("(?<![A-Za-z0-9])AIza[0-9A-Za-z_-]{35}"),
          Pattern.compile("(?<![A-Za-z0-9_])adn_[A-Za-z0-9_-]{40}"),
          Pattern.compile("-----BEGIN (?:RSA |EC |OPENSSH |DSA |PGP )?PRIVATE KEY-----"),
          Pattern.compile(
              "(?<![A-Za-z0-9_-])eyJ[A-Za-z0-9_-]{10,}\\.eyJ[A-Za-z0-9_-]{10,}\\.[A-Za-z0-9_-]{10,}"));
  private static final Pattern ASSIGNED_SECRET =
      Pattern.compile(
          "(?i)(?:secret|token|password|passwd|api[_-]?key|access[_-]?key)[\"']?\\s*[:=]\\s*[\"']?([A-Za-z0-9+/=_.-]{20,})");

  /**
   * A detected value.
   *
   * @param detector the detector id
   * @param start the first character's index
   * @param end the index after the last character
   * @param value the matched text
   * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
   */
  public record Match(String detector, int start, int end, String value) {}

  private static final Map<String, Predicate<String>> VALIDATORS =
      Map.of(
          "email", Detectors::validEmail,
          "phone_es",
              value ->
                  digits(value).length() == 9
                      || digits(value).length() == 11
                      || digits(value).length() == 13,
          "dni_nie", Detectors::validDniNie,
          "iban", Detectors::validIban,
          "card", Detectors::validCard);

  /**
   * Prevents instances: the class only has static helpers.
   *
   * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
   */
  private Detectors() {}

  /**
   * Finds every valid match of the given detectors in a text, without overlaps.
   *
   * @param text the text
   * @param detectors detector ids
   * @return the matches, in text order
   * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
   */
  public static List<Match> find(String text, List<String> detectors) {
    List<Match> found = new ArrayList<>();
    for (String detector : IDS) {
      if (detectors.contains(detector)) {
        found.addAll(candidates(detector, text));
      }
    }
    found.sort((a, b) ->
        a.start() != b.start()
            ? Integer.compare(a.start(), b.start())
            : Integer.compare(b.end(), a.end()));
    List<Match> kept = new ArrayList<>();
    int reached = -1;
    for (Match match : found) {
      if (match.start() >= reached) {
        kept.add(match);
        reached = match.end();
      }
    }
    return kept;
  }

  /**
   * Finds the valid matches of one detector.
   *
   * @param detector the detector id
   * @param text the text
   * @return the matches
   * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
   */
  private static List<Match> candidates(String detector, String text) {
    List<Match> matches = new ArrayList<>();
    if ("secret".equals(detector)) {
      for (Pattern pattern : SECRETS) {
        collect(detector, pattern.matcher(text), 0, matches);
      }
      Matcher assigned = ASSIGNED_SECRET.matcher(text);
      while (assigned.find()) {
        if (entropy(assigned.group(1)) >= 3.5) {
          matches.add(new Match(detector, assigned.start(1), assigned.end(1), assigned.group(1)));
        }
      }
      return matches;
    }
    Pattern pattern =
        switch (detector) {
          case "email" -> EMAIL;
          case "phone_es" -> PHONE_ES;
          case "dni_nie" -> DNI_NIE;
          case "iban" -> IBAN;
          case "card" -> CARD;
          default -> throw new IllegalArgumentException("Unknown detector " + detector);
        };
    Matcher matcher = pattern.matcher(text);
    while (matcher.find()) {
      if (VALIDATORS.get(detector).test(matcher.group())) {
        matches.add(new Match(detector, matcher.start(), matcher.end(), matcher.group()));
      }
    }
    return matches;
  }

  /**
   * Adds every match of a pattern.
   *
   * @param detector the detector id
   * @param matcher the matcher
   * @param group the group that holds the value
   * @param matches collects matches
   * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
   */
  private static void collect(String detector, Matcher matcher, int group, List<Match> matches) {
    while (matcher.find()) {
      matches.add(
          new Match(detector, matcher.start(group), matcher.end(group), matcher.group(group)));
    }
  }

  /**
   * Checks the parts of an address against RFC 5321 length limits.
   *
   * @param value the candidate
   * @return true for a syntactically valid address
   * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
   */
  static boolean validEmail(String value) {
    int at = value.lastIndexOf('@');
    String local = value.substring(0, at);
    return local.length() <= 64
        && value.length() <= 254
        && !local.startsWith(".")
        && !local.endsWith(".")
        && !local.contains("..");
  }

  /**
   * Checks the control letter of a DNI (8 digits) or NIE (X, Y or Z and 7 digits).
   *
   * @param value the candidate
   * @return true when the letter matches the number
   * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
   */
  static boolean validDniNie(String value) {
    String normalised = value.toUpperCase(Locale.ROOT).replace("-", "");
    char first = normalised.charAt(0);
    String number;
    if (first == 'X' || first == 'Y' || first == 'Z') {
      number = (first - 'X') + normalised.substring(1, normalised.length() - 1);
      if (normalised.length() != 9) {
        return false;
      }
    } else {
      number = normalised.substring(0, normalised.length() - 1);
      if (number.length() != 8) {
        return false;
      }
    }
    char letter = normalised.charAt(normalised.length() - 1);
    return DNI_LETTERS.charAt(Integer.parseInt(number) % 23) == letter;
  }

  /**
   * Checks an IBAN with the ISO 13616 mod-97 rule.
   *
   * @param value the candidate, with or without spaces
   * @return true when the check digits are right
   * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
   */
  static boolean validIban(String value) {
    String compact = value.replace(" ", "");
    if (compact.length() < 15 || compact.length() > 34) {
      return false;
    }
    String rearranged = compact.substring(4) + compact.substring(0, 4);
    StringBuilder numeric = new StringBuilder();
    for (char c : rearranged.toCharArray()) {
      numeric.append(Character.isDigit(c) ? String.valueOf(c) : String.valueOf(c - 'A' + 10));
    }
    return new BigInteger(numeric.toString()).mod(BigInteger.valueOf(97)).intValue() == 1;
  }

  /**
   * Checks a card number's length and Luhn digit.
   *
   * @param value the candidate, with or without separators
   * @return true for 13 to 19 digits that pass Luhn
   * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
   */
  static boolean validCard(String value) {
    String number = digits(value);
    if (number.length() < 13 || number.length() > 19 || number.chars().allMatch(c -> c == '0')) {
      return false;
    }
    int sum = 0;
    for (int index = 0; index < number.length(); index++) {
      int digit = number.charAt(number.length() - 1 - index) - '0';
      if (index % 2 == 1) {
        digit = digit * 2 > 9 ? digit * 2 - 9 : digit * 2;
      }
      sum += digit;
    }
    return sum % 10 == 0;
  }

  /**
   * Computes Shannon entropy in bits per character.
   *
   * @param value the text
   * @return the entropy
   * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
   */
  static double entropy(String value) {
    int[] counts = new int[128];
    int total = 0;
    for (char c : value.toCharArray()) {
      if (c < 128) {
        counts[c]++;
        total++;
      }
    }
    double bits = 0;
    for (int count : counts) {
      if (count > 0) {
        double p = (double) count / total;
        bits -= p * Math.log(p) / Math.log(2);
      }
    }
    return bits;
  }

  /**
   * Keeps only the digits of a text.
   *
   * @param value the text
   * @return its digits
   * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
   */
  static String digits(String value) {
    return value.replaceAll("\\D", "");
  }
}
