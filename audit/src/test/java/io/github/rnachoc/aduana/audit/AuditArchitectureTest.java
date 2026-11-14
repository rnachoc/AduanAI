// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Ignacio Rodríguez Caro
package io.github.rnachoc.aduana.audit;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.Test;

/**
 * The audit module stays a pure library: no Spring, files, network, database or clock. Signing keys
 * are generated with the platform's default randomness, which is the one allowed exception.
 *
 * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
 */
class AuditArchitectureTest {

  @Test
  void the_audit_module_has_no_io_framework_or_clock() {
    noClasses()
        .should()
        .dependOnClassesThat()
        .resideInAnyPackage("org.springframework..", "java.nio.file..", "java.net..", "java.sql..")
        .orShould()
        .dependOnClassesThat()
        .belongToAnyOf(java.io.File.class, java.time.Clock.class)
        .orShould()
        .callMethod(java.time.Instant.class, "now")
        .check(
            new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("io.github.rnachoc.aduana.audit"));
  }
}
