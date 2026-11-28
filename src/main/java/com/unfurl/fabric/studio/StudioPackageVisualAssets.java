package com.unfurl.fabric.studio;

import com.fasterxml.jackson.core.type.TypeReference;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Decorator over the tenant catalog (LLD-fabric-studio-api, What a catalog entry is): reads the
 * {@code META-INF/unfurl-studio-visuals.json} manifests under the asset root and adds their presentation to the catalog
 * entries they name. A package visual manifest never creates an entry and never supplies a claim, claim hash or artifact
 * hash: an entry exists only because its claim was read from its artifact.
 */
final class StudioPackageVisualAssets {
    static final String MANIFEST_PATH = "META-INF/unfurl-studio-visuals.json";

    /** Visual manifest keys that come from the claim (connection ports), which a package visual never replaces. */
    private static final Set<String> CLAIM_VISUAL_KEYS = Set.of("ports");
    /** Dynamic-composition keys derived from the claim, which a package visual never replaces. */
    private static final Set<String> CLAIM_COMPOSITION_KEYS = Set.of("rawNeeds", "rawOptionalNeeds", "componentShapeProfile");

    /**
     * Decorates catalog entries with the package visual manifests under {@code assetRoot} that name them.
     *
     * <p>For a named entry, the package's visual manifest keys, asset files (with their computed pins) and
     * {@code dynamicComposition} nesting keys apply, except the keys the claim derives: the connection {@code ports}, the
     * raw needs and the shape profile. A manifest entry that names no catalog entry is skipped, and the log names it.
     *
     * @param entries   the catalog entries, each carrying the claim read from its artifact.
     * @param assetRoot the configured asset root, or {@code null} for none.
     * @return the entries in their order, decorated where a package visual names them.
     */
    List<StudioVisualCatalogEntry> decorate(List<StudioVisualCatalogEntry> entries, Path assetRoot) {
        List<StudioVisualCatalogEntry> visuals = scan(assetRoot);
        if (visuals.isEmpty()) {
            return List.copyOf(entries);
        }
        Map<String, StudioVisualCatalogEntry> byId = new LinkedHashMap<>();
        visuals.forEach(visual -> byId.putIfAbsent(visual.catalogEntryId(), visual));
        List<StudioVisualCatalogEntry> decorated = new ArrayList<>();
        for (StudioVisualCatalogEntry entry : entries) {
            StudioVisualCatalogEntry visual = byId.get(entry.catalogEntryId());
            if (visual == null) {
                decorated.add(entry);
                continue;
            }
            decorated.add(decorated(entry, visual));
        }
        return List.copyOf(decorated);
    }

    /**
     * Names the package visual manifest entries that decorate nothing, for the scan log: each names a catalog entry no
     * admitted or scanned jar provides.
     *
     * @return the catalog entry ids the asset root's package visuals name but {@code entries} does not hold.
     */
    List<String> orphanedVisuals(List<StudioVisualCatalogEntry> entries, Path assetRoot) {
        Set<String> ids = new HashSet<>();
        entries.forEach(entry -> ids.add(entry.catalogEntryId()));
        return scan(assetRoot).stream()
                .map(StudioVisualCatalogEntry::catalogEntryId)
                .filter(id -> !ids.contains(id))
                .distinct()
                .toList();
    }

    /** One entry with a package visual's presentation applied; its claim, hashes and manifest stay its own. */
    private static StudioVisualCatalogEntry decorated(StudioVisualCatalogEntry entry, StudioVisualCatalogEntry visual) {
        Map<String, Object> visualManifest = overlay(entry.visualManifest(), visual.visualManifest(), CLAIM_VISUAL_KEYS);
        Map<String, Object> composition = overlay(entry.dynamicComposition(), visual.dynamicComposition(), CLAIM_COMPOSITION_KEYS);
        Map<String, Object> integrity = visual.visualIntegrity().isEmpty() ? entry.visualIntegrity() : visual.visualIntegrity();
        List<String> warnings = new ArrayList<>(entry.warnings());
        visual.warnings().stream().filter(warning -> !warnings.contains(warning)).forEach(warnings::add);
        return new StudioVisualCatalogEntry(entry.catalogEntryId(), entry.claimHash(), entry.artifactSha256(), visualManifest,
                composition, integrity, warnings, entry.catalogManifest());
    }

    /** {@code base} with {@code overlay}'s keys applied, except the {@code kept} keys, which stay {@code base}'s. */
    private static Map<String, Object> overlay(Map<String, Object> base, Map<String, Object> overlay, Set<String> kept) {
        Map<String, Object> merged = new LinkedHashMap<>(base);
        overlay.forEach((key, value) -> {
            if (!kept.contains(key)) {
                merged.put(key, value);
            }
        });
        return merged;
    }

    /**
     * Reads every package visual manifest under {@code assetRoot}, with each asset's SHA-256 computed from its bytes.
     * The entries it returns are presentation only, input to {@link #decorate}; they are never catalog entries.
     */
    List<StudioVisualCatalogEntry> scan(Path assetRoot) {
        if (assetRoot == null || !Files.isDirectory(assetRoot)) {
            return List.of();
        }
        try (var paths = Files.walk(assetRoot)) {
            return paths
                    .filter(Files::isRegularFile)
                    .filter(path -> path.normalize().endsWith(MANIFEST_PATH))
                    .sorted()
                    .flatMap(path -> readManifest(assetRoot, path).stream())
                    .toList();
        } catch (IOException ex) {
            return List.of();
        }
    }

    private List<StudioVisualCatalogEntry> readManifest(Path assetRoot, Path manifestPath) {
        try {
            Map<String, Object> manifest = StudioJson.mapper().readValue(
                    manifestPath.toFile(),
                    new TypeReference<>() {
                    });
            Object rawEntries = manifest.get("entries");
            if (!(rawEntries instanceof List<?> entries)) {
                return List.of();
            }
            List<StudioVisualCatalogEntry> result = new ArrayList<>();
            for (Object rawEntry : entries) {
                if (rawEntry instanceof Map<?, ?> map) {
                    parseEntry(assetRoot, toStringMap(map)).ifPresent(result::add);
                }
            }
            return result;
        } catch (Exception ex) {
            return List.of();
        }
    }

    private java.util.Optional<StudioVisualCatalogEntry> parseEntry(Path assetRoot, Map<String, Object> entry) {
        String catalogEntryId = stringValue(entry.get("catalogEntryId"), "");
        if (catalogEntryId.isBlank()) {
            return java.util.Optional.empty();
        }
        Map<String, Object> visualIntegrity = visualIntegrity(assetRoot, entry);
        List<String> warnings = new ArrayList<>(stringList(entry.get("warnings")));
        warnings.addAll(stringList(visualIntegrity.get("warnings")));
        return java.util.Optional.of(new StudioVisualCatalogEntry(
                catalogEntryId,
                stringValue(entry.get("claimHash"), ""),
                stringValue(entry.get("artifactSha256"), ""),
                objectMap(entry.get("visualManifest")),
                objectMap(entry.get("dynamicComposition")),
                withoutWarnings(visualIntegrity),
                warnings));
    }

    private Map<String, Object> visualIntegrity(Path assetRoot, Map<String, Object> entry) {
        Map<String, Object> integrity = new LinkedHashMap<>(objectMap(entry.get("visualIntegrity")));
        Object rawAssets = entry.containsKey("assets") ? entry.get("assets") : integrity.get("assets");
        List<Map<String, Object>> assets = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        if (rawAssets instanceof List<?> list) {
            for (Object rawAsset : list) {
                if (rawAsset instanceof Map<?, ?> map) {
                    Map<String, Object> asset = pinnedAsset(assetRoot, toStringMap(map), warnings);
                    if (!asset.isEmpty()) {
                        assets.add(asset);
                    }
                }
            }
        }
        integrity.put("assets", assets);
        if (!warnings.isEmpty()) {
            integrity.put("warnings", warnings);
        }
        if (!integrity.containsKey("visualManifestHash")) {
            integrity.put("visualManifestHash", sha256Hex(entry.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        }
        return integrity;
    }

    private Map<String, Object> pinnedAsset(Path assetRoot, Map<String, Object> asset, List<String> warnings) {
        String assetId = stringValue(asset.get("assetId"), "");
        String path = stringValue(asset.get("path"), "");
        if (assetId.isBlank() || path.isBlank()) {
            warnings.add("package visual asset is missing assetId or path");
            return Map.of();
        }
        Path target = assetRoot.resolve(path).normalize();
        if (!target.startsWith(assetRoot) || !Files.isRegularFile(target)) {
            warnings.add("package visual asset " + assetId + " is missing content at " + path);
            return withStatus(asset, assetId, path, "");
        }
        try {
            String actual = sha256Hex(Files.readAllBytes(target));
            String declared = stringValue(asset.get("sha256"), "");
            if (!declared.isBlank() && !declared.equals(actual)) {
                warnings.add("package visual asset " + assetId + " sha256 mismatch");
                return withStatus(asset, assetId, path, "");
            }
            return withStatus(asset, assetId, path, actual);
        } catch (IOException ex) {
            warnings.add("package visual asset " + assetId + " cannot be read");
            return withStatus(asset, assetId, path, "");
        }
    }

    private Map<String, Object> withStatus(Map<String, Object> asset, String assetId, String path, String sha256) {
        Map<String, Object> pinned = new LinkedHashMap<>(asset);
        pinned.put("assetId", assetId);
        pinned.put("path", path);
        pinned.put("mediaType", stringValue(asset.get("mediaType"), mediaTypeForPath(path)));
        pinned.put("sha256", sha256);
        return pinned;
    }

    private Map<String, Object> withoutWarnings(Map<String, Object> integrity) {
        Map<String, Object> copy = new LinkedHashMap<>(integrity);
        copy.remove("warnings");
        return copy;
    }

    private static Map<String, Object> objectMap(Object value) {
        if (!(value instanceof Map<?, ?> map)) {
            return Map.of();
        }
        return toStringMap(map);
    }

    private static Map<String, Object> toStringMap(Map<?, ?> map) {
        Map<String, Object> copy = new LinkedHashMap<>();
        map.forEach((key, value) -> copy.put(String.valueOf(key), value));
        return copy;
    }

    private static List<String> stringList(Object value) {
        if (!(value instanceof List<?> list)) {
            return List.of();
        }
        return list.stream().map(String::valueOf).toList();
    }

    private static String stringValue(Object value, String fallback) {
        return value == null ? fallback : String.valueOf(value);
    }

    private static String mediaTypeForPath(String path) {
        String normalized = path == null ? "" : path.toLowerCase();
        if (normalized.endsWith(".glb")) {
            return "model/gltf-binary";
        }
        if (normalized.endsWith(".png")) {
            return "image/png";
        }
        return "application/octet-stream";
    }

    private static String sha256Hex(byte[] data) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(data);
            StringBuilder hex = new StringBuilder("sha256:");
            for (byte b : hash) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 not available on this JVM", ex);
        }
    }
}
