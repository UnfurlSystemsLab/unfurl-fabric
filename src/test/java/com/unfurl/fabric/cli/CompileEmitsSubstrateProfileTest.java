package com.unfurl.fabric.cli;

import com.unfurl.fabric.compiler.CompiledContract;
import com.unfurl.substrate.api.SubstrateProfile;
import com.unfurl.substrate.api.SubstrateProfileCodec;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class CompileEmitsSubstrateProfileTest {
    @Test
    void compileWritesProfileAndEmbedsMatchingHash(@TempDir Path dir) throws Exception {
        Path catalog = dir.resolve("catalog");
        CliTestFixtures.writeCatalogJarWithDependencies(
                catalog, "storage.jar", "storage-s3", "storage.put",
                "object-store@^1?substrate=true&provider=s3");
        Path needs = CliTestFixtures.writeNeeds(dir, "storage.put");
        Path contractOut = dir.resolve("contract.yaml");
        Path profileOut = dir.resolve("profile.yaml");

        CliTestFixtures.CliRun result = CliTestFixtures.run("compile",
                "--catalog", catalog.toString(),
                "--needs", needs.toString(),
                "--out", contractOut.toString(),
                "--substrate-profile-out", profileOut.toString());

        assertThat(result.exitCode()).as(result.stderr()).isEqualTo(0);
        assertThat(Files.exists(contractOut)).isTrue();
        assertThat(Files.exists(profileOut)).isTrue();

        CompiledContract contract = CliTestFixtures.readCompiled(contractOut);
        SubstrateProfile profile = new SubstrateProfileCodec().parse(Files.readAllBytes(profileOut));

        assertThat(contract.substrateProfileHash()).isEqualTo(profile.profileHash());
        assertThat(new SubstrateProfileCodec().computeProfileHash(profile))
                .isEqualTo(contract.substrateProfileHash());
        assertThat(profile.portRequirements()).extracting(p -> p.port()).containsExactly("object-store");
    }
}
