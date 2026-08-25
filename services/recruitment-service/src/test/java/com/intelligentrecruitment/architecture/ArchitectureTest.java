package com.intelligentrecruitment.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.importer.ClassFileImporter;
import org.junit.jupiter.api.Test;

class ArchitectureTest {

    @Test
    void domainPackagesDoNotDependOnApiOrInfrastructure() {
        var classes = new ClassFileImporter().importPackages("com.intelligentrecruitment");

        noClasses()
                .that().resideInAPackage("..domain..")
                .should().dependOnClassesThat()
                .resideInAnyPackage("..api..", "..infrastructure..")
                .check(classes);
    }
}

