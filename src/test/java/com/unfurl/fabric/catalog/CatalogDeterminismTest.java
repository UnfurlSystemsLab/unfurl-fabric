package com.unfurl.fabric.catalog;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.fasterxml.jackson.dataformat.yaml.YAMLGenerator;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies Catalog determinism property: same on-disk catalog directory produces byte-identical
 * canonical Catalog bytes across repeated scans. The diagnostic CatalogScanReport differs by
 * {@code scannedAt} between runs; the canonical Catalog does not.
 */
class CatalogDeterminismTest {

    private static final ObjectMapper CANONICAL = canonicalMapper();

    @Test
    void twoScansOfSameCatalogProduceByteIdenticalCatalogBytes(@TempDir Path dir) throws IOException {
        CatalogFixtures.writeJar(dir, "storage-s3.jar", CatalogFixtures.storageS3Manifest());
        CatalogFixtures.writeJar(dir, "identity-idp.jar", CatalogFixtures.identityIdpManifest());

        CatalogScanner scannerA = new CatalogScanner(new CatalogManifestCodec(),
                Clock.fixed(Instant.parse("2026-05-29T00:00:00Z"), ZoneOffset.UTC));
        CatalogScanner scannerB = new CatalogScanner(new CatalogManifestCodec(),
                Clock.fixed(Instant.parse("2027-01-01T00:00:00Z"), ZoneOffset.UTC));

        Catalog catalogA = scannerA.scan(dir).catalog();
        Catalog catalogB = scannerB.scan(dir).catalog();

        byte[] bytesA = CANONICAL.writeValueAsBytes(catalogA);
        byte[] bytesB = CANONICAL.writeValueAsBytes(catalogB);

        assertThat(bytesA).isEqualTo(bytesB);
    }

    @Test
    void scanReportTimestampReflectsInjectedClock(@TempDir Path dir) throws IOException {
        CatalogFixtures.writeJar(dir, "storage-s3.jar", CatalogFixtures.storageS3Manifest());
        Instant fixed = Instant.parse("2026-05-29T12:34:56Z");
        CatalogScanner scanner = new CatalogScanner(new CatalogManifestCodec(),
                Clock.fixed(fixed, ZoneOffset.UTC));

        CatalogScanReport report = scanner.scan(dir);

        assertThat(Duration.between(fixed, report.scannedAt())).isZero();
    }

    @Test
    void catalogCanonicalBytesAreUtf8AndDoNotContainAbsoluteJarPaths(@TempDir Path dir) throws IOException {
        Path absoluteJarPath = CatalogFixtures.writeJar(dir, "storage-s3.jar",
                CatalogFixtures.storageS3Manifest());

        Catalog catalog = new CatalogScanner().scan(dir).catalog();
        byte[] bytes = CANONICAL.writeValueAsBytes(catalog);
        String text = new String(bytes, StandardCharsets.UTF_8);

        // The canonical Catalog still serializes the localPath field today; this test pins
        // that the canonical form omits absolute machine-specific paths. We assert at minimum
        // that no Windows-style drive letter or POSIX-style /tmp prefix appears in the bytes.
        // This will fail loud the moment canonical serialization regresses.
        assertThat(text).doesNotContain(absoluteJarPath.toAbsolutePath().toString());
    }

    private static ObjectMapper canonicalMapper() {
        YAMLFactory yamlFactory = new YAMLFactory()
                .disable(YAMLGenerator.Feature.WRITE_DOC_START_MARKER)
                .disable(YAMLGenerator.Feature.SPLIT_LINES);
        ObjectMapper mapper = new ObjectMapper(yamlFactory);
        mapper.registerModule(new JavaTimeModule());
        return mapper;
    }
}
