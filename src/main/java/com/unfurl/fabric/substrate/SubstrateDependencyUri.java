package com.unfurl.fabric.substrate;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Parsed representation of a single substrate dependency URI of the form
 * {@code <port>@<version-range>?substrate=true[&provider=<name>]}.
 *
 * <p>Pattern: <b>Value Object + static factory ({@link #parse})</b>. The record holds the decoded
 * fields; {@link #parse} is the only construction path from raw text and centralizes all syntax rules,
 * throwing the typed {@link SubstrateProfileException} subtypes on failure.
 *
 * @param port         the substrate port name (no {@code /} or {@code :}); required.
 * @param versionRange the requested version range; defaults to {@code *} (any) when blank.
 * @param provider     optional preferred provider name, or null.
 */
public record SubstrateDependencyUri(
        String port,
        String versionRange,
        String provider
) {
    /**
     * Compact constructor: enforces a non-blank port and defaults a blank version range to {@code *}.
     */
    public SubstrateDependencyUri {
        if (port == null || port.isBlank()) {
            throw new IllegalArgumentException("port is required");
        }
        if (versionRange == null || versionRange.isBlank()) {
            versionRange = "*";
        }
    }

    /**
     * Parse a raw dependency string into a {@link SubstrateDependencyUri}.
     *
     * <p>Requires a query string containing {@code substrate=true}; otherwise the dependency is a normal
     * (non-substrate) dependency and {@link SubstrateProfileException.NotSubstrateDependency} is thrown.
     * The head must be exactly {@code <port>@<version-range>} with a single {@code @}.
     *
     * @param raw the raw dependency string.
     * @return the parsed substrate dependency URI.
     * @throws SubstrateProfileException.NotSubstrateDependency if not marked {@code substrate=true}.
     * @throws SubstrateProfileException.MalformedDependency     if the syntax is otherwise invalid.
     */
    public static SubstrateDependencyUri parse(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new SubstrateProfileException.MalformedDependency(String.valueOf(raw), "dependency is blank");
        }
        int queryAt = raw.indexOf('?');
        if (queryAt < 0) {
            throw new SubstrateProfileException.NotSubstrateDependency(raw);
        }
        String head = raw.substring(0, queryAt);
        Map<String, String> query = parseQuery(raw.substring(queryAt + 1), raw);
        if (!"true".equals(query.get("substrate"))) {
            throw new SubstrateProfileException.NotSubstrateDependency(raw);
        }
        int at = head.indexOf('@');
        if (at <= 0 || at == head.length() - 1 || head.indexOf('@', at + 1) >= 0) {
            throw new SubstrateProfileException.MalformedDependency(raw,
                    "expected <port>@<version-range>?substrate=true");
        }
        String port = head.substring(0, at);
        String version = head.substring(at + 1);
        if (port.contains("/") || port.contains(":") || port.isBlank()) {
            throw new SubstrateProfileException.MalformedDependency(raw, "invalid port name");
        }
        return new SubstrateDependencyUri(port, version, query.get("provider"));
    }

    /**
     * Parse the {@code &}-separated query string into a key→value map, URL-decoding each part.
     *
     * @param rawQuery      the query portion (after {@code ?}).
     * @param rawDependency the full dependency string, for error messages.
     * @return ordered map of query parameters.
     * @throws SubstrateProfileException if the query is empty or a parameter is malformed.
     */
    private static Map<String, String> parseQuery(String rawQuery, String rawDependency) {
        if (rawQuery.isBlank()) {
            throw new SubstrateProfileException.NotSubstrateDependency(rawDependency);
        }
        Map<String, String> query = new LinkedHashMap<>();
        for (String part : rawQuery.split("&")) {
            int eq = part.indexOf('=');
            if (eq <= 0 || eq == part.length() - 1) {
                throw new SubstrateProfileException.MalformedDependency(rawDependency, "malformed query parameter");
            }
            String key = decode(part.substring(0, eq));
            String value = decode(part.substring(eq + 1));
            query.put(key, value);
        }
        return query;
    }

    /**
     * URL-decode a query token using UTF-8.
     *
     * @param value the encoded token.
     * @return the decoded value.
     */
    private static String decode(String value) {
        return URLDecoder.decode(value, StandardCharsets.UTF_8);
    }
}
