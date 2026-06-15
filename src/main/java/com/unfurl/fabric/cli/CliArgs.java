package com.unfurl.fabric.cli;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

final class CliArgs {
    private final Map<String, String> values;

    private CliArgs(Map<String, String> values) {
        this.values = values;
    }

    static CliArgs parse(String[] args) {
        Map<String, String> values = new LinkedHashMap<>();
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
        }
        return new CliArgs(values);
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
