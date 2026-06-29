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
 *
 * <p>Pattern: stateless <b>service</b> (pure comparison; no I/O, no mutation of inputs).
 */
public final class CatalogDriftChecker {

    /**
     * Compare a compiled contract's selected artifacts against the current catalog.
     *
     * <p>Builds a coordinates→entry index of the catalog, then for each selection reports either a
     * SHA mismatch ({@code DriftEntry}) or absence from the catalog ({@code MissingEntry}). A null
     * catalog is treated as "no information" and reported as clean (callers skip the check when no
     * catalog is supplied).
     *
     * @param contract the compiled contract whose selections are checked (required).
     * @param catalog  the current catalog, or null to skip the check.
     * @return a drift report; {@code clean} when no mismatches or missing entries were found.
     * @throws IllegalArgumentException if contract is null.
     */
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
