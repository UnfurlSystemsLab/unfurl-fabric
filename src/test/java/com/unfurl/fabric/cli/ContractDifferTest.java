package com.unfurl.fabric.cli;

import com.unfurl.fabric.compiler.CompiledContract;
import com.unfurl.fabric.compiler.CompiledContractCodec;
import com.unfurl.fabric.verify.ContractDiff;
import com.unfurl.fabric.verify.ContractDiffer;
import com.unfurl.substrate.api.SubstrateProfile;
import com.unfurl.substrate.api.SubstrateProfileCodec;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

/**
 * {@link ContractDiffer}, the one comparison {@code fabric diff} prints and Studio's {@code contract-diff} serializes,
 * over unsigned compiled contracts (LLD-fabric-studio-api, <i>Contract diff and verification</i>). It lives beside the
 * CLI fixtures that compile real contracts.
 */
class ContractDifferTest {
    private final ContractDiffer differ = new ContractDiffer();

    /** Two unsigned compiles of different components: selection, deployment-entry and port deltas, sorted. */
    @Test
    void diffsUnsignedCompiledContracts(@TempDir Path dir) throws Exception {
        Compiled left = compile(dir.resolve("left"), "storage", "storage.put", "object-store@^1?substrate=true&provider=s3");
        Compiled right = compile(dir.resolve("right"), "function", "function.local",
                "function.local@*?substrate=true&provider=flow");

        ContractDiff diff = differ.diff("left-hash", left.contract(), "right-hash", right.contract(),
                left.profile(), right.profile());

        assertThat(diff.canonicalHashChanged()).isTrue();
        assertThat(diff.addedSelections()).containsExactly("com.unfurl:function:1.0.0");
        assertThat(diff.removedSelections()).containsExactly("com.unfurl:storage:1.0.0");
        assertThat(diff.changedSelections()).isEmpty();
        assertThat(diff.addedDeploymentEntries()).containsExactly("com.unfurl:function:1.0.0/function.local");
        assertThat(diff.removedDeploymentEntries()).containsExactly("com.unfurl:storage:1.0.0/storage.put");
        assertThat(diff.substrateProfileHashChanged()).isTrue();
        assertThat(diff.ports().added()).containsExactly("function.local");
        assertThat(diff.ports().removed()).containsExactly("object-store");
    }

    /** The same contract against itself has no delta at all. */
    @Test
    void aContractAgainstItselfHasNoDelta(@TempDir Path dir) throws Exception {
        Compiled only = compile(dir, "storage", "storage.put", "object-store@^1?substrate=true&provider=s3");

        ContractDiff diff = differ.diff("hash", only.contract(), "hash", only.contract(), only.profile(), only.profile());

        assertThat(diff.canonicalHashChanged()).isFalse();
        assertThat(diff.contractIdChanged()).isFalse();
        assertThat(diff.addedSelections()).isEmpty();
        assertThat(diff.removedSelections()).isEmpty();
        assertThat(diff.changedDeploymentShapes()).isEmpty();
        assertThat(diff.ports().added()).isEmpty();
        assertThat(diff.ports().changed()).isEmpty();
    }

    /** Without profiles there is no port delta; with only one, the call is refused. */
    @Test
    void portsNeedBothProfiles(@TempDir Path dir) throws Exception {
        Compiled only = compile(dir, "storage", "storage.put", "object-store@^1?substrate=true&provider=s3");

        assertThat(differ.diff("h", only.contract(), "h", only.contract(), null, null).ports()).isNull();
        assertThatIllegalArgumentException()
                .isThrownBy(() -> differ.diff("h", only.contract(), "h", only.contract(), only.profile(), null));
    }

    /** Compiles one component with the CLI and reads the compiled envelope and profile back. */
    private static Compiled compile(Path dir, String artifact, String capability, String dependency) throws Exception {
        Path catalog = dir.resolve("catalog");
        CliTestFixtures.writeCatalogJarWithDependencies(catalog, artifact + ".jar", artifact, capability, dependency);
        Path needs = CliTestFixtures.writeNeeds(dir, capability);
        Path compiled = dir.resolve("compiled.yaml");
        Path profile = dir.resolve("profile.yaml");
        CliTestFixtures.CliRun run = CliTestFixtures.run("compile", "--catalog", catalog.toString(),
                "--needs", needs.toString(), "--out", compiled.toString(),
                "--substrate-profile-out", profile.toString());
        assertThat(run.exitCode()).as(run.stderr()).isEqualTo(0);
        return new Compiled(new CompiledContractCodec().parse(Files.readAllBytes(compiled)),
                new SubstrateProfileCodec().parse(Files.readAllBytes(profile)));
    }

    private record Compiled(CompiledContract contract, SubstrateProfile profile) {
    }
}
