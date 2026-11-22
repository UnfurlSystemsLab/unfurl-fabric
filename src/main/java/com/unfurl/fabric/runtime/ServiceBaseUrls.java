package com.unfurl.fabric.runtime;

import java.net.URI;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Parser + Value Object: the operator's service base URLs, by provider claim URI (HLD-api-documentation-artifacts §4.1).
 *
 * <p>A base URL overrides the {@code http://<service_name>:<service_port>} a provider's catalog block declares. Each
 * value is an absolute {@code http} or {@code https} URL with no query or fragment; a trailing slash is dropped. The two
 * historical flags remain as aliases on Flow's and Foundry's claim URIs, and giving an alias and an explicit value for
 * the same provider is refused rather than letting one silently win. The CLI and Studio share this one rule.
 */
public final class ServiceBaseUrls {
    /** Flow's claim URI, the target of the {@code --flow-base-url} alias. */
    public static final URI FLOW_CLAIM_URI = URI.create("urn:unfurl:flow:runtime");
    /** Foundry's claim URI, the target of the {@code --foundry-base-url} alias. */
    public static final URI FOUNDRY_CLAIM_URI = URI.create("urn:unfurl:foundry");

    private ServiceBaseUrls() {
    }

    /**
     * Factory: merges {@code claimUri=url} entries with the two aliases.
     *
     * @param entries      every {@code --service-base-url} value; each may hold several comma-separated entries
     * @param flowAlias    the {@code --flow-base-url} value, or null
     * @param foundryAlias the {@code --foundry-base-url} value, or null
     * @return the base URLs by claim URI, sorted, without trailing slashes
     * @throws IllegalArgumentException listing every malformed, repeated or conflicting entry
     */
    public static Map<URI, String> parse(List<String> entries, String flowAlias, String foundryAlias) {
        Map<URI, String> bases = new TreeMap<>();
        List<String> problems = new ArrayList<>();
        for (String value : entries == null ? List.<String>of() : entries) {
            if (value == null || value.isBlank()) continue;
            for (String entry : value.split(",")) {
                int separator = entry.indexOf('=');
                String claim = separator < 0 ? "" : entry.substring(0, separator).trim();
                String url = separator < 0 ? "" : entry.substring(separator + 1).trim();
                if (claim.isEmpty() || url.isEmpty()) {
                    problems.add("'" + entry.trim() + "' is not claimUri=url");
                    continue;
                }
                put(bases, URI.create(claim), url, "--service-base-url", problems);
            }
        }
        if (flowAlias != null && !flowAlias.isBlank()) put(bases, FLOW_CLAIM_URI, flowAlias.trim(), "--flow-base-url", problems);
        if (foundryAlias != null && !foundryAlias.isBlank()) {
            put(bases, FOUNDRY_CLAIM_URI, foundryAlias.trim(), "--foundry-base-url", problems);
        }
        if (!problems.isEmpty()) {
            throw new IllegalArgumentException("service base URLs: " + String.join("; ", problems));
        }
        return Collections.unmodifiableMap(bases);
    }

    /** Adds one base URL, recording a malformed URL or a second value for the same provider as a problem. */
    private static void put(Map<URI, String> bases, URI claim, String url, String source, List<String> problems) {
        String base = url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
        URI parsed;
        try {
            parsed = URI.create(base);
        } catch (IllegalArgumentException malformed) {
            problems.add(source + " for " + claim + ": '" + url + "' is not a URL");
            return;
        }
        if (!parsed.isAbsolute() || !("http".equals(parsed.getScheme()) || "https".equals(parsed.getScheme()))
                || parsed.getHost() == null || parsed.getRawQuery() != null || parsed.getRawFragment() != null) {
            problems.add(source + " for " + claim + ": '" + url + "' must be an absolute http(s) URL without query or fragment");
            return;
        }
        if (bases.putIfAbsent(claim, base) != null) {
            problems.add(claim + " is given more than one base URL (" + source + " repeats it)");
        }
    }
}
