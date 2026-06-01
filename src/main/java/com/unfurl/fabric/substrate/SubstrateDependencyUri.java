package com.unfurl.fabric.substrate;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

public record SubstrateDependencyUri(
        String port,
        String versionRange,
        String provider
) {
    public SubstrateDependencyUri {
        if (port == null || port.isBlank()) {
            throw new IllegalArgumentException("port is required");
        }
        if (versionRange == null || versionRange.isBlank()) {
            versionRange = "*";
        }
    }

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

    private static String decode(String value) {
        return URLDecoder.decode(value, StandardCharsets.UTF_8);
    }
}
