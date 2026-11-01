package com.unfurl.fabric.assembly;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.IOException;
import java.nio.file.*;
import java.util.Map;
import static org.assertj.core.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** Adapter conformance tests: immutable bundle boundaries prevent traversal and partial publication across repository reloads. */
class FileArtifactRepositoryTest {
    /** Byte-identical writes survive reload, while conflicts cannot replace any stored payload. */
    @Test void preservesImmutableBundles(@TempDir Path dir) throws Exception {
        var repository = new FileArtifactRepository(dir);
        repository.put("releases/test/1.0.0", Map.of("nested/input.yaml", new byte[]{1}));
        new FileArtifactRepository(dir).put("releases/test/1.0.0", Map.of("nested/input.yaml", new byte[]{1}));
        assertThatThrownBy(() -> repository.put("releases/test/1.0.0", Map.of("nested/input.yaml", new byte[]{2})))
                .isInstanceOf(IOException.class).hasMessageContaining("immutable");
        assertThat(repository.read("releases/test/1.0.0", "nested/input.yaml")).containsExactly((byte) 1);
        assertThatThrownBy(() -> repository.put("releases/test/1.0.0", Map.of("another.yaml", new byte[]{1})))
                .isInstanceOf(IOException.class);
    }

    /** Invalid keys and late invalid payloads must never expose partial final bundles or escape the root. */
    @Test void rejectsTraversalAndCleansFailedStages(@TempDir Path dir) throws Exception {
        var repository = new FileArtifactRepository(dir);
        for (String unsafe : new String[]{"../escape", "/absolute", "a/../escape", "a\\b", "C:/escape", "a//b", "a/./b"}) {
            assertThatThrownBy(() -> repository.put(unsafe, Map.of("file", new byte[]{1}))).isInstanceOf(IOException.class);
            assertThatThrownBy(() -> repository.read("safe", unsafe)).isInstanceOf(IOException.class);
        }
        assertThatThrownBy(() -> repository.put("releases/new", Map.of("good", new byte[]{1}, "../escape", new byte[]{2})))
                .isInstanceOf(IOException.class);
        assertThat(Files.exists(dir.resolve("releases/new"))).isFalse();
        try (var files = Files.list(dir)) { assertThat(files.noneMatch(p -> p.getFileName().toString().startsWith(".stage-"))).isTrue(); }
    }

    /** Where the OS permits links, neither linked root ancestors nor linked payloads are admitted. */
    @Test void rejectsLinks(@TempDir Path dir) throws Exception {
        Path real = Files.createDirectory(dir.resolve("real"));
        Path link = dir.resolve("link");
        boolean supported = true;
        try { Files.createSymbolicLink(link, real); } catch (IOException | UnsupportedOperationException | SecurityException ex) { supported = false; }
        assumeTrue(supported, "OS does not permit symbolic links");
        assertThatThrownBy(() -> new FileArtifactRepository(link.resolve("nested")).put("key", Map.of("file", new byte[]{1})))
                .isInstanceOf(IOException.class).hasMessageContaining("links");
        var repository = new FileArtifactRepository(real);
        repository.put("key", Map.of("file", new byte[]{1}));
        Files.delete(real.resolve("key/file")); Files.createSymbolicLink(real.resolve("key/file"), dir.resolve("outside"));
        assertThatThrownBy(() -> repository.read("key", "file")).isInstanceOf(IOException.class).hasMessageContaining("links");
    }
}
