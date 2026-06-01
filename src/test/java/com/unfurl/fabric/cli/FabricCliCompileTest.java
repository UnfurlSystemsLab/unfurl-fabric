package com.unfurl.fabric.cli;

import com.unfurl.fabric.compiler.CompiledContract;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class FabricCliCompileTest {
    @Test
    void exactMatchWritesDeterministicContract(@TempDir Path dir) throws Exception {
        Path catalog = dir.resolve("catalog");
        CliTestFixtures.writeCatalogJar(catalog, "storage.jar", "storage-s3", "storage.put");
        Path needs = CliTestFixtures.writeNeeds(dir, "storage.put");
        Path first = dir.resolve("first.yaml");
        Path second = dir.resolve("second.yaml");

        CliTestFixtures.CliRun a = CliTestFixtures.run("compile", "--catalog", catalog.toString(),
                "--needs", needs.toString(), "--out", first.toString());
        CliTestFixtures.CliRun b = CliTestFixtures.run("compile", "--catalog", catalog.toString(),
                "--needs", needs.toString(), "--out", second.toString());

        assertThat(a.exitCode()).isEqualTo(0);
        assertThat(b.exitCode()).isEqualTo(0);
        assertThat(Files.readAllBytes(first)).isEqualTo(Files.readAllBytes(second));
        CompiledContract compiled = CliTestFixtures.readCompiled(first);
        assertThat(compiled.audit().selectionMode()).isEqualTo("AUTO_SINGLE");
    }
}
