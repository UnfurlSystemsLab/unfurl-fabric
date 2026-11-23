package com.unfurl.fabric.runtime;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.unfurl.dcp.documentation.Visibility;
import com.unfurl.dcp.documentation.VisibilityPolicy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Value Object: the operator's documentation settings for an API catalog in the composition
 * (HLD-api-documentation-artifacts §4.2).
 *
 * <p>Each field is null (or the file absent) when the operator did not give it, so {@link #given()} can tell an
 * explicit setting from a default: the settings are written only into a {@code documentation.catalog} provider's
 * binding, and a setting given for a composition without one is refused rather than dropped. {@link #resolved()} applies
 * Fabric's option defaults, which are then written explicitly into the binding.
 *
 * @param defaultVisibility {@code --documentation-visibility-default}, or null
 * @param capabilities      per-capability visibility from the visibility file; empty when not given
 * @param apiDocuments      per-API-document visibility from the visibility file; empty when not given
 * @param intervalMs        {@code --catalog-observation-interval-ms}, or null
 * @param maxBackoffMs      {@code --catalog-observation-max-backoff-ms}, or null
 * @param visibilityFile    the visibility file that was read, or null; kept so a refusal can name it
 */
public record DocumentationOptions(
        Visibility defaultVisibility,
        Map<String, Visibility> capabilities,
        Map<String, Visibility> apiDocuments,
        Long intervalMs,
        Long maxBackoffMs,
        Path visibilityFile
) {
    /** Fabric's default visibility of a capability or API document. */
    public static final Visibility DEFAULT_VISIBILITY = Visibility.INTERNAL;
    /** Fabric's default observation interval. */
    public static final long DEFAULT_INTERVAL_MS = 10_000;
    /** The shortest observation interval the API catalog accepts (its LLD §2.2): one probe per root per second at most. */
    public static final long MIN_INTERVAL_MS = 1_000;
    /** Fabric's default maximum observation backoff. */
    public static final long DEFAULT_MAX_BACKOFF_MS = 60_000;
    /** The only keys a visibility file may hold. */
    private static final Set<String> FILE_KEYS = Set.of("capabilities", "api_documents");

    /** Canonical constructor: sorted, immutable maps; an interval of at least {@link #MIN_INTERVAL_MS} and a positive backoff not below it. */
    public DocumentationOptions {
        capabilities = Collections.unmodifiableMap(new TreeMap<>(capabilities == null ? Map.of() : capabilities));
        apiDocuments = Collections.unmodifiableMap(new TreeMap<>(apiDocuments == null ? Map.of() : apiDocuments));
        if (intervalMs != null && intervalMs < MIN_INTERVAL_MS) {
            throw new IllegalArgumentException("--catalog-observation-interval-ms must be at least " + MIN_INTERVAL_MS
                    + ", not " + intervalMs);
        }
        if (maxBackoffMs != null && maxBackoffMs <= 0) {
            throw new IllegalArgumentException("--catalog-observation-max-backoff-ms must be positive, not " + maxBackoffMs);
        }
        long interval = intervalMs == null ? DEFAULT_INTERVAL_MS : intervalMs;
        long backoff = maxBackoffMs == null ? DEFAULT_MAX_BACKOFF_MS : maxBackoffMs;
        if (backoff < interval) {
            throw new IllegalArgumentException("--catalog-observation-max-backoff-ms (" + backoff
                    + ") must be at least the observation interval (" + interval + ")");
        }
    }

    /** Null Object: no documentation setting given (the default). */
    public static DocumentationOptions none() {
        return new DocumentationOptions(null, Map.of(), Map.of(), null, null, null);
    }

    /**
     * Factory: the CLI's or Studio's settings, with the visibility file read when one is named.
     *
     * @param defaultVisibility the default's wire value ({@code public}, {@code internal}, {@code private}), or null
     * @param visibilityFile    the visibility file, or null
     * @param intervalMs        the observation interval, or null
     * @param maxBackoffMs      the maximum backoff, or null
     * @throws IllegalArgumentException for an unknown visibility, a malformed file or invalid observation settings
     */
    public static DocumentationOptions of(String defaultVisibility, Path visibilityFile, Long intervalMs, Long maxBackoffMs) {
        Visibility visibility = defaultVisibility == null || defaultVisibility.isBlank() ? null
                : visibility(defaultVisibility.trim(), "--documentation-visibility-default");
        Map<String, Visibility> capabilities = Map.of();
        Map<String, Visibility> apiDocuments = Map.of();
        if (visibilityFile != null) {
            JsonNode root = readFile(visibilityFile);
            capabilities = visibilities(root.path("capabilities"), visibilityFile + " capabilities");
            apiDocuments = visibilities(root.path("api_documents"), visibilityFile + " api_documents");
        }
        return new DocumentationOptions(visibility, capabilities, apiDocuments, intervalMs, maxBackoffMs, visibilityFile);
    }

    /** Whether the operator gave any documentation setting at all. */
    public boolean given() {
        return defaultVisibility != null || visibilityFile != null || intervalMs != null || maxBackoffMs != null;
    }

    /** The options the operator gave, as {@code --flag} names, for a refusal message. */
    public List<String> givenFlags() {
        List<String> flags = new ArrayList<>();
        if (defaultVisibility != null) flags.add("--documentation-visibility-default");
        if (visibilityFile != null) flags.add("--documentation-visibility");
        if (intervalMs != null) flags.add("--catalog-observation-interval-ms");
        if (maxBackoffMs != null) flags.add("--catalog-observation-max-backoff-ms");
        return flags;
    }

    /** The visibility policy these settings describe, with Fabric's default visibility when none was given. */
    public VisibilityPolicy policy() {
        return new VisibilityPolicy(defaultVisibility == null ? DEFAULT_VISIBILITY : defaultVisibility, capabilities, apiDocuments);
    }

    /**
     * The catalog binding's {@code configuration.values} (HLD-api-documentation-artifacts §4.2): every value written
     * explicitly, defaults included, with visibilities as their lower-case wire values and maps sorted.
     */
    public Map<String, Object> bindingValues() {
        VisibilityPolicy policy = policy();
        Map<String, Object> visibility = new LinkedHashMap<>();
        visibility.put("default", policy.defaultVisibility().wireValue());
        visibility.put("capabilities", wire(capabilities));
        visibility.put("api_documents", wire(apiDocuments));
        Map<String, Object> observation = new LinkedHashMap<>();
        observation.put("interval_ms", intervalMs == null ? DEFAULT_INTERVAL_MS : intervalMs);
        observation.put("max_backoff_ms", maxBackoffMs == null ? DEFAULT_MAX_BACKOFF_MS : maxBackoffMs);
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("documentation_visibility", visibility);
        values.put("observation", observation);
        return values;
    }

    /** A visibility map as sorted wire values. */
    private static Map<String, String> wire(Map<String, Visibility> map) {
        Map<String, String> wire = new TreeMap<>();
        map.forEach((key, value) -> wire.put(key, value.wireValue()));
        return wire;
    }

    /** Reads and shape-checks the visibility file: a YAML map holding only {@code capabilities} and {@code api_documents}. */
    private static JsonNode readFile(Path file) {
        JsonNode root;
        try {
            root = new ObjectMapper(new YAMLFactory()).readTree(Files.readAllBytes(file));
        } catch (IOException unreadable) {
            throw new IllegalArgumentException("--documentation-visibility " + file + " cannot be read: " + unreadable.getMessage());
        }
        if (root == null || root.isMissingNode() || root.isNull()) {
            return new ObjectMapper().createObjectNode();
        }
        if (!root.isObject()) {
            throw new IllegalArgumentException("--documentation-visibility " + file + " must be a YAML map");
        }
        for (Iterator<String> keys = root.fieldNames(); keys.hasNext(); ) {
            String key = keys.next();
            if (!FILE_KEYS.contains(key)) {
                throw new IllegalArgumentException("--documentation-visibility " + file + ": unknown key '" + key
                        + "'; only capabilities and api_documents are allowed (the default is --documentation-visibility-default)");
            }
        }
        return root;
    }

    /** One map of the visibility file: names to {@code public}, {@code internal} or {@code private}. */
    private static Map<String, Visibility> visibilities(JsonNode node, String where) {
        if (node.isMissingNode() || node.isNull()) return Map.of();
        if (!node.isObject()) throw new IllegalArgumentException(where + " must be a map of name to visibility");
        Map<String, Visibility> map = new TreeMap<>();
        node.fields().forEachRemaining(field -> {
            if (!field.getValue().isTextual()) throw new IllegalArgumentException(where + "." + field.getKey() + " must be text");
            map.put(field.getKey(), visibility(field.getValue().asText(), where + "." + field.getKey()));
        });
        return map;
    }

    /** Parses one visibility wire value, naming where it came from on failure. */
    private static Visibility visibility(String value, String where) {
        try {
            return Visibility.fromWireValue(value);
        } catch (IllegalArgumentException unknown) {
            throw new IllegalArgumentException(where + ": '" + value + "' is not public, internal or private");
        }
    }
}
