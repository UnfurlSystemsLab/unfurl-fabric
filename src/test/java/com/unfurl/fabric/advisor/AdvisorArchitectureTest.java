package com.unfurl.fabric.advisor;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import org.junit.jupiter.api.Test;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

class AdvisorArchitectureTest {
    @Test
    void advisorCoreDoesNotDependOnHttpClientsOrProviderSdks() {
        JavaClasses classes = new ClassFileImporter().importPackages("com.unfurl.fabric.advisor");

        noClasses().should().dependOnClassesThat().resideInAnyPackage(
                        "java.net.http..",
                        "com.anthropic..",
                        "com.openai..",
                        "com.azure.ai.openai..")
                .check(classes);
    }
}
