package com.unfurl.fabric.verify;

import com.unfurl.fabric.catalog.Catalog;
import com.unfurl.fabric.catalog.CatalogEntry;
import com.unfurl.fabric.compiler.CompiledContract;
import com.unfurl.fabric.compiler.SelectionRecord;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Compares the SHA-256s declared in a {@link CompiledContract}'s {@code SelectionRecord}s
 * against the JARs in the current {@link Catalog}. Drift can mean a JAR was rebuilt with
 * the same coordinates (a real integrity concern) or a selection's coordinates are no longer
 * present in the catalog at all.
 *
 * <p>Catalog drift is a separate concern from signature verification — a signed contract may
 * verify cleanly even if the catalog has drifted, and a deployment server without the catalog
 * present may legitimately skip this check.
 */
public final class CatalogDriftChecker {

    public CatalogDriftReport check(CompiledContract contract, Catalog catalog) {
        if (contract == null) {
            throw new IllegalArgumentException("contract is required");
        }
        if (catalog == null) {
            // Caller is responsible for skipping this checker when no catalog is supplied;
            // a null catalog passed here is treated as no-information ⇒ clean.
            return CatalogDriftReport.noDrift();
        }

        Map<String, CatalogEntry> byCoords = new HashMap<>();
        for (CatalogEntry e : catalog.entries()) {
            byCoords.put(e.artifact().coordinates(), e);
        }

        List<CatalogDriftReport.DriftEntry> drifts = new ArrayList<>();
        List<CatalogDriftReport.MissingEntry> missing = new ArrayList<>();

        for (SelectionRecord selection : contract.selections()) {
            String coords = selection.artifact().coordinates();
            String contractedSha = selection.artifact().sha256();
            CatalogEntry entry = byCoords.get(coords);
            if (entry == null) {
                missing.add(new CatalogDriftReport.MissingEntry(coords, contractedSha));
                continue;
            }
            String catalogSha = entry.artifact().sha256();
            if (!contractedSha.equals(catalogSha)) {
                drifts.add(new CatalogDriftReport.DriftEntry(coords, contractedSha, catalogSha));
            }
        }

        boolean clean = drifts.isEmpty() && missing.isEmpty();
        return new CatalogDriftReport(drifts, missing, clean);
    }
}
