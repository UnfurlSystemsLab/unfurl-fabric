package com.unfurl.fabric.verify;

import com.unfurl.fabric.artifact.ArtifactDescriptor;
import com.unfurl.fabric.catalog.Catalog;
import com.unfurl.fabric.catalog.CatalogEntry;
import com.unfurl.fabric.compiler.CompiledContract;
import com.unfurl.fabric.signing.SigningTestFixtures;
import com.unfurl.fabric.testing.FabricTestFixtures;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class CatalogDriftCheckerTest {
    private final CatalogDriftChecker checker = new CatalogDriftChecker();

    @Test
    void reportsCleanWhenCatalogMatchesSelections() {
        CompiledContract contract = SigningTestFixtures.sampleCompiledContract();
        Catalog catalog = FabricTestFixtures.catalog(FabricTestFixtures.entry("storage-s3", "storage.put"));

        CatalogDriftReport report = checker.check(contract, catalog);

        assertThat(report.clean()).isTrue();
        assertThat(report.hashDrifts()).isEmpty();
        assertThat(report.missingFromCatalog()).isEmpty();
    }

    @Test
    void reportsHashDriftWhenSameCoordinatesHaveDifferentSha() {
        CompiledContract contract = SigningTestFixtures.sampleCompiledContract();
        CatalogEntry entry = FabricTestFixtures.entry("storage-s3", "storage.put");
        CatalogEntry drifted = new CatalogEntry(
                new ArtifactDescriptor(entry.artifact().coordinates(), entry.artifact().packaging(),
                        entry.artifact().source(),
                        "cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc",
                        entry.artifact().signature()),
                entry.claimDescriptor(),
                entry.metadata(),
                entry.localPath());

        CatalogDriftReport report = checker.check(contract, new Catalog(List.of(drifted)));

        assertThat(report.clean()).isFalse();
        assertThat(report.hashDrifts()).hasSize(1);
        assertThat(report.hashDrifts().get(0).coordinates()).isEqualTo("com.unfurl:storage-s3:1.0.0");
        assertThat(report.missingFromCatalog()).isEmpty();
    }

    @Test
    void reportsMissingWhenSelectionCoordinatesAreAbsent() {
        CompiledContract contract = SigningTestFixtures.sampleCompiledContract();

        CatalogDriftReport report = checker.check(contract, Catalog.empty());

        assertThat(report.clean()).isFalse();
        assertThat(report.hashDrifts()).isEmpty();
        assertThat(report.missingFromCatalog()).hasSize(1);
        assertThat(report.missingFromCatalog().get(0).coordinates())
                .isEqualTo("com.unfurl:storage-s3:1.0.0");
    }
}
