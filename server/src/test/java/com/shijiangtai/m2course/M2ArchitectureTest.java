package com.shijiangtai.m2course;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

/** M2 模块边界：domain 不依赖框架 / 其他层；application 不依赖 api / infrastructure；api 不依赖 infrastructure。 */
@AnalyzeClasses(packages = "com.shijiangtai", importOptions = ImportOption.DoNotIncludeTests.class)
class M2ArchitectureTest {

    @ArchTest
    static final ArchRule domainHasNoFrameworkImports = noClasses()
            .that().resideInAPackage("..m2course.domain..")
            .should().dependOnClassesThat().resideInAnyPackage(
                    "org.springframework..",
                    "tools.jackson..",
                    "com.fasterxml.jackson..",
                    "jakarta.persistence..",
                    "..m2course.api..",
                    "..m2course.application..",
                    "..m2course.infrastructure..");

    @ArchTest
    static final ArchRule applicationDoesNotDependOnApiOrInfrastructure = noClasses()
            .that().resideInAPackage("..m2course.application..")
            .should().dependOnClassesThat().resideInAnyPackage(
                    "..m2course.api..",
                    "..m2course.infrastructure..");

    @ArchTest
    static final ArchRule apiDoesNotDependOnInfrastructure = noClasses()
            .that().resideInAPackage("..m2course.api..")
            .should().dependOnClassesThat().resideInAPackage("..m2course.infrastructure..");

    @ArchTest
    static final ArchRule m2DoesNotDependOnOtherModules = noClasses()
            .that().resideInAPackage("..m2course..")
            .should().dependOnClassesThat().resideInAnyPackage(
                    "..m3classroom..", "..m1auth..", "..m4eval..",
                    "..m5question..", "..m6training..", "..m7teacher..");
}
