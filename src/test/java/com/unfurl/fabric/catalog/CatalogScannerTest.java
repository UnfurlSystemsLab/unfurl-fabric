package com.unfurl.fabric.catalog;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CatalogScannerTest {

    private final CatalogScanner scanner = new CatalogScanner();

    @Test
    void scansDirectoryAndProducesCatalogEntriesForEachValidJar(@TempDir Path dir) throws IOException {
        CatalogFixtures.writeJar(dir, "storage-s3.jar", CatalogFixtures.storageS3Manifest());
        CatalogFixtures.writeJar(dir, "storage-postgres.jar", CatalogFixtures.storagePostgresManifest());
        CatalogFixtures.writeJar(dir, "identity-idp.jar", CatalogFixtures.identityIdpManifest());

        CatalogScanReport report = scanner.scan(dir);

        assertThat(report.catalog().entries()).hasSize(3);
        assertThat(report.skippedEntries()).isEmpty();
        assertThat(report.scanSource()).isEqualTo(dir);
        assertThat(report.scannedAt()).isNotNull();

        List<String> coordinates = report.catalog().entries().stream()
                .map(e -> e.artifact().coordinates())
                .toList();
        assertThat(coordinates).contains(
                "com.unfurl:unfurl-storage-s3:1.2.0",
                "com.unfurl:unfurl-storage-postgres:1.0.0",
                "com.unfurl:unfurl-identity-idp:2.1.0");
    }

    @Test
    void blockedLifecycleEntriesAreReadSuccessfullyAndDeferredToTrustOrMatcher(@TempDir Path dir) throws IOException {
        CatalogFixtures.writeJar(dir, "active.jar", CatalogFixtures.storageS3Manifest());
        CatalogFixtures.writeJar(dir, "blocked.jar", CatalogFixtures.blockedManifest());

        CatalogScanReport report = scanner.scan(dir);

        assertThat(report.catalog().entries()).hasSize(2);
        assertThat(report.skippedEntries()).isEmpty();
        CatalogEntry blockedEntry = report.catalog().entries().stream()
                .filter(e -> e.metadata().lifecycle().status() == LifecycleStatus.BLOCKED)
                .findFirst()
                .orElseThrow();
        assertThat(blockedEntry.artifact().coordinates()).isEqualTo("com.unfurl:unfurl-vendor-legacy:0.9.0");
    }

    @Test
    void malformedManifestProducesSkippedEntryWithReason(@TempDir Path dir) throws IOException {
        CatalogFixtures.writeJar(dir, "ok.jar", CatalogFixtures.storageS3Manifest());
        CatalogFixtures.writeJar(dir, "malformed.jar", "not: a: valid: yaml: at: all:");

        CatalogScanReport report = scanner.scan(dir);

        assertThat(report.catalog().entries()).hasSize(1);
        assertThat(report.skippedEntries()).hasSize(1);
        SkippedEntry skipped = report.skippedEntries().get(0);
        assertThat(skipped.reason()).isEqualTo(SkippedEntry.SkipReason.MALFORMED_MANIFEST);
    }

    @Test
    void jarWithoutCatalogManifestIsSkipped(@TempDir Path dir) throws IOException {
        CatalogFixtures.writeJar(dir, "ok.jar", CatalogFixtures.storageS3Manifest());
        CatalogFixtures.writeEmptyJar(dir, "empty.jar");

        CatalogScanReport report = scanner.scan(dir);

        assertThat(report.catalog().entries()).hasSize(1);
        assertThat(report.skippedEntries()).hasSize(1);
        assertThat(report.skippedEntries().get(0).reason())
                .isEqualTo(SkippedEntry.SkipReason.MISSING_MANIFEST);
    }

    @Test
    void rejectsNonDirectoryPath(@TempDir Path dir) throws IOException {
        Path file = Files.createFile(dir.resolve("not-a-directory.txt"));
        assertThatThrownBy(() -> scanner.scan(file))
                .isInstanceOf(CatalogScanException.class)
                .hasMessageContaining("not a directory");
    }

    @Test
    void computesArtifactSha256OverJarBytes(@TempDir Path dir) throws IOException {
        Path jarPath = CatalogFixtures.writeJar(dir, "storage-s3.jar", CatalogFixtures.storageS3Manifest());

        CatalogScanReport report = scanner.scan(dir);

        CatalogEntry entry = report.catalog().entries().get(0);
        assertThat(entry.artifact().sha256()).matches("[0-9a-f]{64}");

        byte[] jarBytes = Files.readAllBytes(jarPath);
        String expected = CatalogManifestCodec.sha256Hex(jarBytes);
        assertThat(entry.artifact().sha256()).isEqualTo(expected);
    }

    @Test
    void catalogEntriesAreSortedByCoordinatesAscThenSha256Asc(@TempDir Path dir) throws IOException {
        CatalogFixtures.writeJar(dir, "z.jar", CatalogFixtures.identityIdpManifest());
        CatalogFixtures.writeJar(dir, "a.jar", CatalogFixtures.storageS3Manifest());
        CatalogFixtures.writeJar(dir, "m.jar", CatalogFixtures.storagePostgresManifest());

        CatalogScanReport report = scanner.scan(dir);

        List<String> coords = report.catalog().entries().stream()
                .map(e -> e.artifact().coordinates())
                .toList();
        List<String> sorted = coords.stream().sorted().toList();
        assertThat(coords).isEqualTo(sorted);
    }
}
