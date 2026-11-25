package com.unfurl.fabric.studio;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link StudioCompiledContractStore}: content-addressed, partitioned by tenant, durable across instances, and refusing
 * bytes that no longer match their hash (LLD-fabric-studio-api, <i>Durable compiled contracts</i>).
 */
class StudioCompiledContractStoreTest {
    private static final byte[] CONTENT = "compiled: envelope\n".getBytes(StandardCharsets.UTF_8);

    /** Bytes put are read back by their hash, by a new instance over the same folder too, and stored once. */
    @Test
    void storesContentByItsHashDurably(@TempDir Path dir) throws Exception {
        StudioCompiledContractStore store = StudioCompiledContractStore.inFolder(dir);
        String sha256 = store.put("tenant-a", CONTENT);

        assertThat(sha256).matches("[0-9a-f]{64}");
        assertThat(store.put("tenant-a", CONTENT)).isEqualTo(sha256);
        assertThat(StudioCompiledContractStore.inFolder(dir).get("tenant-a", sha256)).hasValue(CONTENT);
        try (Stream<Path> files = Files.walk(dir)) {
            assertThat(files.filter(Files::isRegularFile)).singleElement()
                    .satisfies(file -> assertThat(file.getFileName().toString()).isEqualTo(sha256));
        }
    }

    /** A tenant reads only its own content, even knowing another tenant's hash. */
    @Test
    void keepsTenantsApart(@TempDir Path dir) {
        for (StudioCompiledContractStore store : new StudioCompiledContractStore[]{
                StudioCompiledContractStore.inFolder(dir), StudioCompiledContractStore.inMemory()}) {
            String sha256 = store.put("tenant-a", CONTENT);
            assertThat(store.get("tenant-b", sha256)).isEmpty();
            assertThat(store.get("tenant-a", sha256)).hasValue(CONTENT);
        }
    }

    /** A damaged file no longer hashes to its name, so it is never returned. */
    @Test
    void refusesBytesThatNoLongerMatchTheirHash(@TempDir Path dir) throws Exception {
        StudioCompiledContractStore store = StudioCompiledContractStore.inFolder(dir);
        String sha256 = store.put("tenant-a", CONTENT);
        try (Stream<Path> files = Files.walk(dir)) {
            Path stored = files.filter(Files::isRegularFile).findFirst().orElseThrow();
            Files.writeString(stored, "edited");
        }

        assertThat(store.get("tenant-a", sha256)).isEmpty();
    }

    /** A malformed or unknown hash is simply absent; it can never name a path outside the store. */
    @Test
    void answersNothingForAMalformedOrUnknownHash(@TempDir Path dir) {
        StudioCompiledContractStore store = StudioCompiledContractStore.inFolder(dir);

        assertThat(store.get("tenant-a", "../../etc/passwd")).isEmpty();
        assertThat(store.get("tenant-a", "0".repeat(64))).isEmpty();
        assertThat(store.get("tenant-a", null)).isEmpty();
    }

    /** The folder sits beside the state file; without a state file the store is in memory. */
    @Test
    void placesTheFolderBesideTheStateFile(@TempDir Path dir) {
        StudioCompiledContractStore beside = StudioCompiledContractStore.forState(
                new StudioStateStore(dir.resolve("studio-state.json")));

        assertThat(beside.root()).hasValue(dir.resolve(StudioCompiledContractStore.FOLDER).toAbsolutePath().normalize());
        assertThat(StudioCompiledContractStore.forState(null).root()).isEmpty();
    }
}
