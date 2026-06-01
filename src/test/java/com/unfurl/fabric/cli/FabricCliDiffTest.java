package com.unfurl.fabric.cli;

import com.unfurl.fabric.signing.SigningTestFixtures;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.security.KeyPair;

import static org.assertj.core.api.Assertions.assertThat;

class FabricCliDiffTest {
    @Test
    void diffsSignedContractsAndOptionalSubstrateProfiles(@TempDir Path dir) throws Exception {
        SignedProfilePaths left = compileAndSign(dir.resolve("left"), "storage", "storage.put",
                "object-store@^1?substrate=true&provider=s3");
        SignedProfilePaths right = compileAndSign(dir.resolve("right"), "function", "function.local",
                "function.local@*?substrate=true&provider=flow");

        CliTestFixtures.CliRun result = CliTestFixtures.run("diff",
                "--left", left.signed().toString(),
                "--right", right.signed().toString(),
                "--left-profile", left.profile().toString(),
                "--right-profile", right.profile().toString());

        assertThat(result.exitCode()).as(result.stderr()).isEqualTo(0);
        assertThat(result.stdout())
                .contains("Fabric contract diff")
                .contains("canonicalHashChanged=true")
                .contains("Selection delta")
                .contains("addedSelections:")
                .contains("com.unfurl:function:1.0.0")
                .contains("removedSelections:")
                .contains("com.unfurl:storage:1.0.0")
                .contains("Substrate profile delta")
                .contains("substrateProfileHashChanged=true")
                .contains("addedPorts:")
                .contains("function.local")
                .contains("removedPorts:")
                .contains("object-store");
    }

    @Test
    void reportsSubstrateHashDeltaWithoutProfileDetails(@TempDir Path dir) throws Exception {
        SignedProfilePaths left = compileAndSign(dir.resolve("left"), "storage", "storage.put",
                "object-store@^1?substrate=true&provider=s3");
        SignedProfilePaths right = compileAndSign(dir.resolve("right"), "function", "function.local",
                "function.local@*?substrate=true&provider=flow");

        CliTestFixtures.CliRun result = CliTestFixtures.run("diff",
                "--left", left.signed().toString(),
                "--right", right.signed().toString());

        assertThat(result.exitCode()).as(result.stderr()).isEqualTo(0);
        assertThat(result.stdout())
                .contains("substrateProfileHashChanged=true")
                .contains("profileDetail=<not provided>");
    }

    private SignedProfilePaths compileAndSign(Path dir, String artifact, String capability, String dependency) throws Exception {
        Path catalog = dir.resolve("catalog");
        CliTestFixtures.writeCatalogJarWithDependencies(catalog, artifact + ".jar", artifact, capability, dependency);
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
        return new SignedProfilePaths(signed, profile);
    }

    private record SignedProfilePaths(Path signed, Path profile) {
    }
}
