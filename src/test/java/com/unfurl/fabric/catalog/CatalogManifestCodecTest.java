package com.unfurl.fabric.catalog;

import com.unfurl.dcp.claim.Claim;
import com.unfurl.substrate.api.BindingMode;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CatalogManifestCodecTest {

    private final CatalogManifestCodec codec = new CatalogManifestCodec();

    @Test
    void parsesTwoBlockManifestWithClaimAndCatalogSections() {
        byte[] bytes = CatalogFixtures.storageS3Manifest().getBytes(StandardCharsets.UTF_8);

        ParsedManifest parsed = codec.parse(bytes);

        Claim claim = parsed.claim();
        assertThat(claim.identity().name()).isEqualTo("unfurl-storage-s3");
        assertThat(claim.identity().version()).isEqualTo("1.2.0");
        assertThat(claim.offers()).hasSize(1);
        assertThat(claim.offers().get(0).capability()).isEqualTo("storage.put");

        assertThat(parsed.lifecycle().status()).isEqualTo(LifecycleStatus.ACTIVE);
        assertThat(parsed.authoredArtifact().coordinates()).isEqualTo("com.unfurl:unfurl-storage-s3:1.2.0");
        assertThat(parsed.binding().defaultMode()).isEqualTo(BindingMode.IN_PROCESS);
        assertThat(parsed.binding().supportedModes()).contains(BindingMode.IN_PROCESS);
    }

    @Test
    void rejectsMissingClaimBlock() {
        byte[] bytes = CatalogFixtures.missingClaimBlockManifest().getBytes(StandardCharsets.UTF_8);

        assertThatThrownBy(() -> codec.parse(bytes))
                .isInstanceOf(CatalogScanException.class)
                .hasMessageContaining("claim");
    }

    @Test
    void rejectsMissingCatalogBlock() {
        byte[] bytes = CatalogFixtures.missingCatalogBlockManifest().getBytes(StandardCharsets.UTF_8);

        assertThatThrownBy(() -> codec.parse(bytes))
                .isInstanceOf(CatalogScanException.class)
                .hasMessageContaining("catalog");
    }

    @Test
    void preservesCapabilityAndArtifactVersionsAsDistinctFields() {
        byte[] bytes = CatalogFixtures.manifest(
                "urn:unfurl:storage:s3",
                "unfurl-storage-s3",
                "COMPONENT",
                "1.2.0",
                "Unfurl",
                "stuff",
                "ACTIVE",
                "com.unfurl:unfurl-storage-s3:1.2.0",
                "IN_PROCESS",
                "storage.put",
                "2.0.0")
                .getBytes(StandardCharsets.UTF_8);

        ParsedManifest parsed = codec.parse(bytes);

        assertThat(parsed.claim().identity().version()).isEqualTo("1.2.0");
        assertThat(parsed.claim().offers().get(0).version()).isEqualTo("2.0.0");
        assertThat(parsed.authoredArtifact().coordinates())
                .isEqualTo("com.unfurl:unfurl-storage-s3:1.2.0");
    }

    @Test
    void claimHashIsDeterministicAcrossRepeatedSerialization() {
        byte[] bytes = CatalogFixtures.storageS3Manifest().getBytes(StandardCharsets.UTF_8);
        ParsedManifest parsed = codec.parse(bytes);

        String first = codec.computeClaimHash(parsed.claim());
        String second = codec.computeClaimHash(parsed.claim());

        assertThat(first).isEqualTo(second);
        assertThat(first).matches("[0-9a-f]{64}");
    }

    @Test
    void claimHashChangesWhenClaimContentChanges() {
        byte[] a = CatalogFixtures.storageS3Manifest().getBytes(StandardCharsets.UTF_8);
        byte[] b = CatalogFixtures.storagePostgresManifest().getBytes(StandardCharsets.UTF_8);

        String hashA = codec.computeClaimHash(codec.parse(a).claim());
        String hashB = codec.computeClaimHash(codec.parse(b).claim());

        assertThat(hashA).isNotEqualTo(hashB);
    }
}
