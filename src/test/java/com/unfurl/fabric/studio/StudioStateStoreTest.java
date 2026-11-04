package com.unfurl.fabric.studio;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Specification (RELIABILITY.md, Studio State): the state file is replaced through a temporary file on every save, so a
 * stopped process never leaves a truncated file, and no temporary file is left behind.
 */
class StudioStateStoreTest {

    /** Saving creates missing parent folders, replaces an existing file, round-trips through load, and leaves no temp file. */
    @Test
    void saveReplacesTheStateFileAndLeavesNoTemporaryFile(@TempDir Path dir) throws Exception {
        Path statePath = dir.resolve("nested").resolve("studio-state.json");
        StudioStateStore store = new StudioStateStore(statePath);

        store.save(StudioStateStore.State.empty());
        Files.writeString(statePath, "{ \"stale\": true, \"padding\": \"" + "x".repeat(4096) + "\" }");
        store.save(StudioStateStore.State.empty());

        assertThat(store.load()).isEqualTo(StudioStateStore.State.empty().normalized());
        assertThat(Files.readString(statePath)).doesNotContain("stale", "padding");
        try (Stream<Path> files = Files.list(statePath.getParent())) {
            assertThat(files.map(path -> path.getFileName().toString())).containsExactly("studio-state.json");
        }
    }
}
