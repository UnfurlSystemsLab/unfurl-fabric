package com.unfurl.fabric.catalog;

import com.unfurl.dcp.claim.Claim;
import com.unfurl.dcp.claim.ClaimKey;
import com.unfurl.dcp.contract.CompositionContract;
import com.unfurl.fabric.compiler.CompiledContract;
import com.unfurl.fabric.compiler.SelectionRecord;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Resolver + Value Object: the catalog entries a compiled contract selected, each verified against the pin the contract
 * holds (HLD-api-documentation-artifacts §4.0).
 *
 * <p>The signed contract pins every selection by artifact coordinates, artifact SHA-256 and claim hash, but carries
 * neither the claims nor their catalog blocks. Commands that need them (the DCP runtime bundle, runtime bindings and the
 * documentation check) read the catalog the contract was compiled from and resolve each selection here, so all of them
 * read one verified set. A selection is matched by its artifact SHA-256, the exact jar; its coordinates and claim hash
 * must then equal the selection's.
 *
 * <p>Every problem is collected and reported together in one {@link Refusal}, so an operator fixes the catalog once.
 */
public final class SelectedCatalogEntries {
    /** No scanned jar has a selection's artifact SHA-256. */
    public static final String CATALOG_ENTRY_MISSING = "CATALOG_ENTRY_MISSING";
    /** The jar was found but under other coordinates, or its claim hash differs from the pinned one. */
    public static final String CATALOG_CLAIM_MISMATCH = "CATALOG_CLAIM_MISMATCH";

    /** The resolved entries, in the contract's selection order. */
    private final List<CatalogEntry> entries;
    /** The same entries keyed by artifact coordinates, which a child contract's metadata names. */
    private final Map<String, CatalogEntry> byCoordinates;

    private SelectedCatalogEntries(List<CatalogEntry> entries) {
        this.entries = List.copyOf(entries);
        Map<String, CatalogEntry> index = new LinkedHashMap<>();
        entries.forEach(entry -> index.put(entry.artifact().coordinates(), entry));
        this.byCoordinates = Collections.unmodifiableMap(index);
    }

    /**
     * Factory: resolves every selection of {@code compiled} in {@code catalog}.
     *
     * @param compiled the compiled contract whose selections are resolved
     * @param catalog  the scanned catalog the contract was compiled from
     * @return the verified entries
     * @throws Refusal naming every selection that is missing ({@value #CATALOG_ENTRY_MISSING}) or whose pin does not match
     *                 ({@value #CATALOG_CLAIM_MISMATCH})
     */
    public static SelectedCatalogEntries resolve(CompiledContract compiled, Catalog catalog) {
        Map<String, CatalogEntry> bySha = new HashMap<>();
        catalog.entries().forEach(entry -> bySha.putIfAbsent(entry.artifact().sha256(), entry));
        List<CatalogEntry> resolved = new ArrayList<>();
        List<String> problems = new ArrayList<>();
        for (SelectionRecord selection : compiled.selections()) {
            CatalogEntry entry = bySha.get(selection.artifact().sha256());
            if (entry == null) {
                problems.add(CATALOG_ENTRY_MISSING + ": the catalog has no jar with the selected artifact "
                        + selection.artifact().coordinates() + " (sha256 " + selection.artifact().sha256() + ")");
            } else if (!entry.artifact().coordinates().equals(selection.artifact().coordinates())
                    || !entry.claimDescriptor().claimHash().equals(selection.claimHash())) {
                problems.add(CATALOG_CLAIM_MISMATCH + ": the catalog's jar for " + selection.artifact().coordinates()
                        + " is " + entry.artifact().coordinates() + " with claim hash " + entry.claimDescriptor().claimHash()
                        + ", but the contract pins claim hash " + selection.claimHash());
            } else {
                resolved.add(entry);
            }
        }
        if (!problems.isEmpty()) {
            throw new Refusal(String.join("; ", problems));
        }
        return new SelectedCatalogEntries(resolved);
    }

    /** The resolved entries, in the contract's selection order. */
    public List<CatalogEntry> entries() {
        return entries;
    }

    /**
     * The entry that provides a child contract, found by the artifact coordinates its metadata records.
     *
     * @return the entry, or empty when the child names no coordinates or none was selected under them
     */
    public Optional<CatalogEntry> forContract(CompositionContract child) {
        Object coordinates = child.metadata() == null || child.metadata().extensions() == null
                ? null : child.metadata().extensions().get("artifactCoordinates");
        return coordinates == null ? Optional.empty() : Optional.ofNullable(byCoordinates.get(coordinates.toString()));
    }

    /** The resolved claims by DCP claim identity, as DCP's documentation gate takes them. */
    public Map<ClaimKey, Claim> claims() {
        Map<ClaimKey, Claim> claims = new LinkedHashMap<>();
        entries.forEach(entry -> claims.put(ClaimKey.of(entry.claimDescriptor().claim()), entry.claimDescriptor().claim()));
        return Collections.unmodifiableMap(claims);
    }

    /** Refusal: the catalog does not hold the contract's selections as pinned. Its message lists every problem. */
    public static final class Refusal extends IllegalStateException {
        /** @param message every problem, each starting with its code */
        public Refusal(String message) {
            super(message);
        }
    }
}
