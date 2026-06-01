package com.unfurl.fabric.cli;

import com.unfurl.fabric.signing.SigningTestFixtures;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.security.KeyPair;

import static org.assertj.core.api.Assertions.assertThat;

class FabricCliExplainSubstrateTest {
    @Test
    void explainsSubstrateProfileStandalone(@TempDir Path dir) throws Exception {
        CompiledPaths paths = compileAndSign(dir, "storage", "storage.put",
                "object-store@^1?substrate=true&provider=s3");

        CliTestFixtures.CliRun result = CliTestFixtures.run("explain-substrate",
                "--profile", paths.profile().toString());

        assertThat(result.exitCode()).as(result.stderr()).isEqualTo(0);
        assertThat(result.stdout())
                .contains("Substrate profile explanation")
                .contains("profileHashValid=true")
                .contains("supportedBindingModes=[IN_PROCESS]")
                .contains("closedWorldPorts=1")
                .contains("- object-store")
                .contains("capability=object-store")
                .contains("versionRange=^1")
                .contains("providerPreference=s3")
                .contains("Closed-world note");
    }

    @Test
    void verifiesProfileHashAgainstSignedContractWhenProvided(@TempDir Path dir) throws Exception {
        CompiledPaths paths = compileAndSign(dir, "storage", "storage.put",
                "object-store@^1?substrate=true&provider=s3");

        CliTestFixtures.CliRun result = CliTestFixtures.run("explain-substrate",
                "--profile", paths.profile().toString(),
                "--contract", paths.signed().toString());

        assertThat(result.exitCode()).as(result.stderr()).isEqualTo(0);
        assertThat(result.stdout())
                .contains("contractProfileHash=")
                .contains("profileHashMatchesContract=true");
    }

    @Test
    void failsWhenProfileHashDoesNotMatchSignedContract(@TempDir Path dir) throws Exception {
        CompiledPaths storage = compileAndSign(dir.resolve("storage"), "storage", "storage.put",
                "object-store@^1?substrate=true&provider=s3");
        CompiledPaths function = compileAndSign(dir.resolve("function"), "function", "function.local",
                "function.local@*?substrate=true&provider=flow");

        CliTestFixtures.CliRun result = CliTestFixtures.run("explain-substrate",
                "--profile", function.profile().toString(),
                "--contract", storage.signed().toString());

        assertThat(result.exitCode()).isEqualTo(1);
        assertThat(result.stdout()).contains("profileHashMatchesContract=false");
        assertThat(result.stderr()).contains("substrate profile hash does not match contract");
    }

    private CompiledPaths compileAndSign(Path dir, String name, String capability, String dependency) throws Exception {
        Path catalog = dir.resolve("catalog");
        CliTestFixtures.writeCatalogJarWithDependencies(catalog, name + ".jar", name, capability, dependency);
        Path needs = CliTestFixtures.writeNeeds(dir, capability);
        Path compiled = dir.resolve("compiled.yaml");
        Path profile = dir.resolve("profile.yaml");
        CliTestFixtures.CliRun compile = CliTestFixtures.run("compile",
                "--catalog", catalog.toString(),
                "--needs", needs.toString(),
                "--out", compiled.toString(),
                "--substrate-profile-out", profile.toString());
        assertThat(compile.exitCode()).as(compile.stderr()).isEqualTo(0);

        KeyPair pair = SigningTestFixtures.generateEcKeyPair();
        Path privateKey = SigningTestFixtures.writePrivateKeyPem(dir, "key.pem", pair.getPrivate());
        Path publicKey = SigningTestFixtures.writePublicKeyPem(dir, "pub.pem", pair.getPublic());
        Path signed = dir.resolve("signed.yaml");
        CliTestFixtures.CliRun sign = CliTestFixtures.run("sign",
                "--contract", compiled.toString(),
                "--key", privateKey.toString(),
                "--public-key", publicKey.toString(),
                "--out", signed.toString());
        assertThat(sign.exitCode()).as(sign.stderr()).isEqualTo(0);
        return new CompiledPaths(profile, signed);
    }

    private record CompiledPaths(Path profile, Path signed) {
    }
}
