package com.unfurl.fabric.catalog;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

/**
 * Builder of the catalog's companion index (HLD-in-process-component-hosting §3, companion resolution). Jars without a
 * catalog manifest are offered here; each is indexed by the coordinates of its one {@code pom.properties}. The rules
 * make resolution independent of scan order:
 * <ul>
 *   <li>A jar with no {@code pom.properties}, or several (a shaded jar), is not a companion candidate.</li>
 *   <li>Jars with the same coordinates and the same SHA-256 are one artifact; every path is kept.</li>
 *   <li>Jars with the same coordinates and different SHA-256 are a conflict: neither is chosen, and every hosted entry
 *       naming those coordinates is unresolved with {@code CATALOG_COMPANION_CONFLICT}.</li>
 * </ul>
 * Reading is metadata access only; no catalog code runs.
 */
final class CompanionIndex {
    static final String MISSING = CompanionCoordinates.MISSING;
    static final String CONFLICT = CompanionCoordinates.CONFLICT;

    /** Every candidate jar by coordinates, then by SHA-256, with the paths holding those bytes. */
    private final Map<String, Map<String, List<Path>>> candidates = new TreeMap<>();

    /**
     * Offers a jar that has no catalog manifest.
     *
     * @return empty when the jar was indexed as a companion candidate; otherwise why it is not one
     */
    Optional<String> offer(Path jar) {
        CompanionCoordinates.Identity identity;
        byte[] bytes;
        try {
            // One read: the coordinates come from the same bytes that are hashed.
            bytes = Files.readAllBytes(jar);
            identity = CompanionCoordinates.read(bytes);
        } catch (IOException unreadable) {
            return Optional.of("not a companion candidate: unreadable (" + unreadable.getMessage() + ")");
        }
        Optional<String> coordinates = identity.single();
        if (coordinates.isEmpty()) {
            return Optional.of(identity.refusal());
        }
        candidates.computeIfAbsent(coordinates.get(), c -> new TreeMap<>())
                .computeIfAbsent(sha256(bytes), s -> new ArrayList<>()).add(jar);
        return Optional.empty();
    }

    /**
     * Checks a hosted entry's companions.
     *
     * @return empty when every companion resolves to one artifact; otherwise the failure, starting with its code
     */
    Optional<String> unresolved(HostedActivation activation) {
        List<String> missing = new ArrayList<>();
        List<String> conflicts = new ArrayList<>();
        for (String companion : activation.companions()) {
            Map<String, List<Path>> byHash = candidates.get(companion);
            if (byHash == null) {
                missing.add(companion);
            } else if (byHash.size() > 1) {
                List<String> files = new ArrayList<>();
                byHash.forEach((sha, paths) -> paths.forEach(path -> files.add(path.getFileName() + " (" + sha + ")")));
                conflicts.add(companion + " has different bytes in " + String.join(", ", files));
            }
        }
        if (!conflicts.isEmpty()) return Optional.of(CONFLICT + ": " + String.join("; ", conflicts));
        if (!missing.isEmpty()) return Optional.of(MISSING + ": the catalog has no companion " + String.join(", ", missing));
        return Optional.empty();
    }

    /** Returns the resolved companions an activation names, in its order; call only after {@link #unresolved} is empty. */
    List<CompanionArtifact> resolve(HostedActivation activation) {
        Map<String, CompanionArtifact> artifacts = artifacts();
        return activation.companions().stream().map(artifacts::get).toList();
    }

    /** Returns every unambiguous companion by coordinates; conflicting coordinates are left out. */
    Map<String, CompanionArtifact> artifacts() {
        Map<String, CompanionArtifact> artifacts = new LinkedHashMap<>();
        candidates.forEach((coordinates, byHash) -> {
            if (byHash.size() != 1) return;
            Map.Entry<String, List<Path>> only = byHash.entrySet().iterator().next();
            List<Path> paths = only.getValue().stream().sorted().toList();
            artifacts.put(coordinates, new CompanionArtifact(coordinates, only.getKey(), paths.get(0), paths));
        });
        return artifacts;
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }
}
