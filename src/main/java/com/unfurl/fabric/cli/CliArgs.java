package com.unfurl.fabric.cli;

import java.util.List;
import java.util.ArrayList;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

final class CliArgs {
    private final Map<String, String> values;
    private final Map<String, List<String>> repeated;

    private CliArgs(Map<String, String> values, Map<String, List<String>> repeated) {
        this.values = values;
        this.repeated = repeated;
    }

    static CliArgs parse(String[] args) {
        Map<String, String> values = new LinkedHashMap<>();
        Map<String, List<String>> repeated = new LinkedHashMap<>();
        for (int i = 0; i < args.length; i++) {
            String token = args[i];
            if (!token.startsWith("--")) {
                throw FabricCliException.usage("unexpected positional argument: " + token);
            }
            String key = token.substring(2);
            if (key.isBlank()) {
                throw FabricCliException.usage("blank flag");
            }
            if ("auto-select-best".equals(key) || "dry-run".equals(key) || "apply".equals(key)) {
                values.put(key, "true");
                continue;
            }
            if (i + 1 >= args.length || args[i + 1].startsWith("--")) {
                throw FabricCliException.usage("missing value for --" + key);
            }
            values.put(key, args[++i]);
            repeated.computeIfAbsent(key, k -> new ArrayList<>()).add(values.get(key));
        }
        return new CliArgs(values, repeated);
    }

    /** Returns every value given for a repeatable flag, in order; {@link #get} returns only the last one. */
    List<String> all(String key) {
        return List.copyOf(repeated.getOrDefault(key, List.of()));
    }

    String get(String key) {
        return values.get(key);
    }

    boolean has(String key) {
        return values.containsKey(key);
    }

    Path requiredPath(String key) {
        String value = values.get(key);
        if (value == null || value.isBlank()) {
            throw FabricCliException.usage("missing required --" + key);
        }
        return Path.of(value);
    }

    Path optionalPath(String key) {
        String value = values.get(key);
        return value == null || value.isBlank() ? null : Path.of(value);
    }
}
