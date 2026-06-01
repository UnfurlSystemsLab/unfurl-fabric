package com.unfurl.fabric.cli;

import com.unfurl.fabric.compiler.CompiledContract;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

class FabricCliCompileBlocksOnAmbiguousTest {
    @Test
    void handlesAmbiguousCompileSelectionModes(@TempDir Path dir) throws Exception {
        Path catalog = dir.resolve("catalog");
        CliTestFixtures.writeCatalogJar(catalog, "a.jar", "storage-a", "storage.put");
        CliTestFixtures.writeCatalogJar(catalog, "b.jar", "storage-b", "storage.put");
        Path needs = CliTestFixtures.writeNeeds(dir, "storage.put");

        CliTestFixtures.CliRun blocked = CliTestFixtures.run("compile", "--catalog", catalog.toString(),
                "--needs", needs.toString(), "--out", dir.resolve("blocked.yaml").toString());
        assertThat(blocked.exitCode()).isEqualTo(2);
        assertThat(blocked.stderr()).contains("ambiguous match").containsPattern("cand-[0-9a-f]{12}");

        String candidateId = firstCandidateId(blocked.stderr());
        Path manualOut = dir.resolve("manual.yaml");
        CliTestFixtures.CliRun manual = CliTestFixtures.run("compile", "--catalog", catalog.toString(),
                "--needs", needs.toString(), "--select", candidateId, "--out", manualOut.toString());
        assertThat(manual.exitCode()).isEqualTo(0);
        CompiledContract manualContract = CliTestFixtures.readCompiled(manualOut);
        assertThat(manualContract.audit().selectionMode()).isEqualTo("MANUAL");

        Path autoOut = dir.resolve("auto.yaml");
        CliTestFixtures.CliRun auto = CliTestFixtures.run("compile", "--catalog", catalog.toString(),
                "--needs", needs.toString(), "--auto-select-best", "--out", autoOut.toString());
        assertThat(auto.exitCode()).isEqualTo(0);
        CompiledContract autoContract = CliTestFixtures.readCompiled(autoOut);
        assertThat(autoContract.audit().selectionMode()).isEqualTo("AUTO_BEST_SCORE");
    }

    private static String firstCandidateId(String text) {
        Matcher matcher = Pattern.compile("cand-[0-9a-f]{12}").matcher(text);
        if (!matcher.find()) {
            throw new AssertionError("no candidate id in: " + text);
        }
        return matcher.group();
    }
}
