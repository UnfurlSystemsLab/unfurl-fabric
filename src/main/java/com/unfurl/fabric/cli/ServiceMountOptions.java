package com.unfurl.fabric.cli;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Pattern;

/**
 * Parser: the operator mounts of declared services (HLD-api-documentation-artifacts §4.1; emitter HLD-E §4.0d,
 * <em>Operator mounts</em>). Each {@code --service-config <service_name>=<dir>} and {@code --service-secrets
 * <service_name>=<dir>} (repeatable, or comma-separated) becomes the target option {@value #CONFIG_OPTION}{@code <name>}
 * or {@value #SECRETS_OPTION}{@code <name>}, an absolute path resolved once against the working directory.
 *
 * <p>Only the syntax is checked here. Whether a service needs, may have or cannot use the mounts depends on the signed
 * plan and the identity contract, and the emitter decides that ({@code OperatorMounts}), so the rule has one owner.
 */
final class ServiceMountOptions {
    /** The emitter's target option prefix for a service's config root ({@code OperatorMounts.CONFIG_OPTION}). */
    static final String CONFIG_OPTION = "serviceConfigPath.";
    /** The emitter's target option prefix for a service's secrets root ({@code OperatorMounts.SECRETS_OPTION}). */
    static final String SECRETS_OPTION = "serviceSecretsPath.";
    /** A service name: a lower-case DNS label, as the plan's {@code ServiceEndpoint} requires. */
    private static final Pattern DNS_LABEL = Pattern.compile("^[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?$");

    private ServiceMountOptions() {
    }

    /**
     * Factory: the target options for every given mount.
     *
     * @param configs every {@code --service-config} value
     * @param secrets every {@code --service-secrets} value
     * @return option key to absolute directory, sorted
     * @throws FabricCliException a usage error listing every malformed entry and every service named twice for one flag
     */
    static Map<String, String> parse(List<String> configs, List<String> secrets) {
        Map<String, String> options = new TreeMap<>();
        List<String> problems = new ArrayList<>();
        collect(configs, "--service-config", CONFIG_OPTION, options, problems);
        collect(secrets, "--service-secrets", SECRETS_OPTION, options, problems);
        if (!problems.isEmpty()) {
            throw FabricCliException.usage("service mounts: " + String.join("; ", problems));
        }
        return Collections.unmodifiableMap(options);
    }

    /** Adds one flag's entries, recording each malformed or repeated one as a problem. */
    private static void collect(List<String> values, String flag, String prefix, Map<String, String> options,
                                List<String> problems) {
        for (String value : values == null ? List.<String>of() : values) {
            if (value == null || value.isBlank()) continue;
            for (String entry : value.split(",")) {
                int separator = entry.indexOf('=');
                String service = separator < 0 ? "" : entry.substring(0, separator).trim();
                String dir = separator < 0 ? "" : entry.substring(separator + 1).trim();
                if (service.isEmpty() || dir.isEmpty()) {
                    problems.add(flag + " '" + entry.trim() + "' is not <service_name>=<dir>");
                } else if (!DNS_LABEL.matcher(service).matches()) {
                    problems.add(flag + " '" + service + "' is not a service name (a lower-case DNS label)");
                } else if (options.putIfAbsent(prefix + service, Path.of(dir).toAbsolutePath().normalize().toString()) != null) {
                    problems.add(flag + " names service " + service + " more than once");
                }
            }
        }
    }
}
