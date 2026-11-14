// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Ignacio Rodríguez Caro
package io.github.rnachoc.aduana.policy;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.Test;

/**
 * The policy module stays a pure library: no Spring, no files, no network, no database, no clock
 * and no randomness.
 *
 * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
 */
class PolicyArchitectureTest {

  private static final JavaClasses CLASSES =
      new ClassFileImporter()
          .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
          .importPackages("io.github.rnachoc.aduana.policy");

  @Test
  void the_policy_module_has_no_io_framework_clock_or_randomness() {
    noClasses()
        .should()
        .dependOnClassesThat()
        .resideInAnyPackage(
            "org.springframework..", "java.nio.file..", "java.net..", "java.sql..", "javax.sql..")
        .orShould()
        .dependOnClassesThat()
        .belongToAnyOf(
            java.io.File.class,
            java.io.FileInputStream.class,
            java.io.FileReader.class,
            java.time.Clock.class,
            java.util.Random.class,
            java.security.SecureRandom.class)
        .orShould()
        .callMethod(java.time.Instant.class, "now")
        .orShould()
        .callMethod(System.class, "currentTimeMillis")
        .check(CLASSES);
  }
}
