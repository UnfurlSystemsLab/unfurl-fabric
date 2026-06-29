package com.unfurl.fabric.catalog;

import com.unfurl.dcp.claim.ClaimValidator;
import com.unfurl.dcp.validation.Severity;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration-style resource test for the Fabric module's embedded catalog manifest.
 *
 * <p>Pattern: <b>Contract test</b>. It verifies that the checked-in
 * {@code META-INF/unfurl-catalog.yaml} resource remains parseable by the package scanner and valid
 * under the protocol-owned DCP claim validator used by Studio admission.
 */
class FabricCatalogManifestResourceTest {

    /**
     * Resource contract: the packaged Fabric artifact must expose a DCP-valid catalog manifest
     * so Studio admission and catalog scans do not reject it as a metadata-less JAR.
     */
    @Test
    void fabricCatalogManifestResourceIsScannerParseableAndDcpValid() throws Exception {
        byte[] manifestBytes = readManifestResource();
        ParsedManifest parsed = new CatalogManifestCodec().parse(manifestBytes);

        assertThat(parsed.claim().identity().uri().toString()).isEqualTo("urn:unfurl:fabric:studio");
        assertThat(parsed.authoredArtifact().coordinates()).isEqualTo("com.unfurl.fabric:unfurl-fabric:0.1.0-SNAPSHOT");
        assertThat(parsed.binding().supportedModes()).extracting(Enum::name).contains("IN_PROCESS", "REMOTE_HTTP");
        assertThat(new ClaimValidator().validate(parsed.claim()).diagnostics())
                .noneMatch(diagnostic -> diagnostic.severity() == Severity.ERROR);
    }

    /**
     * Test helper: loads the manifest exactly as Maven will place it in the module test
     * classpath, preserving UTF-8 bytes for the scanner parser.
     */
    private static byte[] readManifestResource() throws Exception {
        try (var stream = FabricCatalogManifestResourceTest.class
                .getClassLoader()
                .getResourceAsStream("META-INF/unfurl-catalog.yaml")) {
            assertThat(stream)
                    .as("META-INF/unfurl-catalog.yaml is packaged as a test/runtime resource")
                    .isNotNull();
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8).getBytes(StandardCharsets.UTF_8);
        }
    }
}
