package com.shijiangtai.m3classroom;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

@AnalyzeClasses(packages = "com.shijiangtai",
        importOptions = ImportOption.DoNotIncludeTests.class)
class ArchitectureTest {

    @ArchTest
    static final ArchRule domainHasNoFrameworkImports = noClasses()
            .that().resideInAPackage("..m3classroom.domain..")
            .should().dependOnClassesThat().resideInAnyPackage(
                    "org.springframework..",
                    "tools.jackson..",
                    "jakarta.persistence..",
                    "..m3classroom.api..",
                    "..m3classroom.application..",
                    "..m3classroom.infrastructure..");

    @ArchTest
    static final ArchRule applicationDoesNotDependOnApiOrInfrastructure = noClasses()
            .that().resideInAPackage("..m3classroom.application..")
            .should().dependOnClassesThat().resideInAnyPackage(
                    "..m3classroom.api..",
                    "..m3classroom.infrastructure..");

    @ArchTest
    static final ArchRule apiDoesNotDependOnInfrastructure = noClasses()
            .that().resideInAPackage("..m3classroom.api..")
            .should().dependOnClassesThat().resideInAPackage(
                    "..m3classroom.infrastructure..");
}
