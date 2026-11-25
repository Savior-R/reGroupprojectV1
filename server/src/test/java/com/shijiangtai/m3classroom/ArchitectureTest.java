package com.shijiangtai.m3classroom;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.core.importer.Location;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

@AnalyzeClasses(packages = "com.shijiangtai",
        importOptions = {ImportOption.DoNotIncludeTests.class,
                ArchitectureTest.ExcludeGradleTestOutput.class})
class ArchitectureTest {

    /**
     * {@link ImportOption.DoNotIncludeTests} only recognises the legacy Maven layout
     * ({@code /test-classes/} or {@code /classes/test/}). Gradle's modern
     * {@code /classes/java/test/} output would otherwise be analysed as production code and
     * report test helpers as layering violations.
     */
    public static final class ExcludeGradleTestOutput implements ImportOption {
        @Override
        public boolean includes(Location location) {
            return !location.contains("/classes/java/test/")
                    && !location.contains("/classes/test/")
                    && !location.contains("/test-classes/");
        }
    }

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
