package com.unfurl.fabric.studio;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link StudioPathFingerprint}: the fingerprint of files a preview reads from outside Studio state changes when a file
 * is edited, added or removed, or a missing path appears, and stays equal otherwise.
 */
class StudioPathFingerprintTest {
    /** Each change to a watched path changes the fingerprint; reading it again without a change does not. */
    @Test
    void followsEveryFileChange(@TempDir Path dir) throws Exception {
        Path workflows = dir.resolve("workflows");
        Map<String, Path> paths = new HashMap<>();
        paths.put("option:flowWorkflowsPath", workflows);
        paths.put("signingPrivateKey", null);

        String missing = StudioPathFingerprint.of(paths);
        Files.createDirectories(workflows);
        Path workflow = Files.writeString(workflows.resolve("workflow.yaml"), "steps: []");
        Files.setLastModifiedTime(workflow, FileTime.fromMillis(1_000_000L));
        String present = StudioPathFingerprint.of(paths);
        assertThat(present).as("a repaired, missing folder").isNotEqualTo(missing);
        assertThat(StudioPathFingerprint.of(paths)).as("unchanged").isEqualTo(present);

        Files.writeString(workflow, "steps: [one]");
        Files.setLastModifiedTime(workflow, FileTime.fromMillis(2_000_000L));
        String edited = StudioPathFingerprint.of(paths);
        assertThat(edited).as("an edited file").isNotEqualTo(present);

        Files.writeString(workflows.resolve("nested.yaml"), "steps: []");
        assertThat(StudioPathFingerprint.of(paths)).as("an added file").isNotEqualTo(edited);
    }
}
