package com.unfurl.fabric.runtime;

import com.unfurl.dcp.componentconfig.ComponentConfigurationSchema;
import com.unfurl.dcp.componentconfig.ComponentConfigurationValidator;
import com.unfurl.dcp.componentconfig.ConfigFailure;
import com.unfurl.dcp.componentconfig.ConfigKey;
import com.unfurl.dcp.componentconfig.ConfigValueType;
import com.unfurl.dcp.contract.CompositionContract;
import com.unfurl.dcp.runtimebinding.ConfigRef;
import com.unfurl.dcp.runtimebinding.Configuration;
import com.unfurl.deployment.plan.BindingPlanEntry;
import com.unfurl.fabric.catalog.CatalogEntry;
import com.unfurl.fabric.compiler.CompiledContract;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

/**
 * The deployment layer of hosted components' configuration (HLD-in-process-component-hosting §6; DCP LLD, <em>Component
 * configuration</em>). It reads each component's packaged {@code META-INF/unfurl-config.yaml} with DCP's own schema
 * parser, turns {@code --component-config <claimUri>#<key>=<value>} flags into a runtime binding {@link Configuration}
 * per component, and validates the result with DCP's own validator before any binding is written. Composer and host
 * therefore apply one rule set; the host checks again at admission, after resolving references.
 *
 * <p>Flag values: {@code config://...} becomes a reference in {@code config_refs}; anything else, {@code secret://}
 * references included, is an inline value, converted to the key's declared type ({@code integer}, {@code boolean},
 * comma-separated {@code list<T>}) so DCP's strict typing applies. Nothing is guessed: an unknown key, a value that
 * does not convert, or a missing deployment key is reported, all at once, naming component, key and rule.
 */
public final class HostedComponentConfiguration {
    private HostedComponentConfiguration() { }

    /**
     * Reads a jar's packaged configuration schema, as data.
     *
     * @return the schema, or empty when the component packages none
     * @throws com.unfurl.dcp.componentconfig.ComponentConfigurationException when the packaged file is invalid
     * @throws IOException                                                    when the jar cannot be read
     */
    public static Optional<ComponentConfigurationSchema> packagedSchema(Path jar) throws IOException {
        try (JarFile file = new JarFile(jar.toFile(), false)) {
            JarEntry entry = file.getJarEntry(ComponentConfigurationSchema.RESOURCE);
            if (entry == null) return Optional.empty();
            try (InputStream in = file.getInputStream(entry)) {
                return Optional.of(ComponentConfigurationSchema.parse(in.readAllBytes()));
            }
        }
    }

    /** Returns the provider claim URIs of the compiled contract's hosted child contracts: those whose plan entry has a host. */
    /**
     * Planning view of a hosted component's packaged schema (HLD-in-process-component-hosting §6, §10): the keys each
     * deployment must supply ({@code source: deployment}, with their types) and the packaged defaults it may change
     * ({@code tighten} or {@code replace}), as one {@code plan} line. Fixed keys are not listed, because no deployment
     * can change them.
     *
     * @return the line, or empty when the jar packages no {@code META-INF/unfurl-config.yaml}
     * @throws IllegalArgumentException when the jar cannot be read or its schema is invalid
     */
    public static Optional<String> deploymentKeysLine(CatalogEntry entry) {
        if (entry.localPath() == null) {
            return Optional.empty();
        }
        Optional<ComponentConfigurationSchema> schema;
        try {
            schema = packagedSchema(entry.localPath());
        } catch (IOException ex) {
            throw new IllegalArgumentException("unable to read " + entry.localPath() + ": " + ex.getMessage(), ex);
        }
        return schema.map(s -> {
            List<String> required = new ArrayList<>();
            List<String> overridable = new ArrayList<>();
            new TreeMap<>(s.keys()).values().forEach(key -> {
                String type = key.type().wireName() + (key.list() ? " list" : "");
                if (key.source() == ConfigKey.Source.DEPLOYMENT) {
                    required.add(key.name() + " (" + type + ")");
                } else if (key.override() != ConfigKey.OverridePolicy.FIXED) {
                    overridable.add(key.name() + " (" + key.override().name().toLowerCase(java.util.Locale.ROOT) + ")");
                }
            });
            return "deployment keys: " + entry.claimDescriptor().claim().identity().uri()
                    + " requires " + (required.isEmpty() ? "none" : String.join(", ", required))
                    + "; may change " + (overridable.isEmpty() ? "none" : String.join(", ", overridable));
        });
    }

    public static Set<URI> hostedProviders(CompiledContract compiled) {
        Set<URI> providers = new LinkedHashSet<>();
        if (compiled.bindingPlan() == null) return providers;
        for (CompositionContract child : compiled.childContracts()) {
            Object coordinates = child.metadata() == null ? null : child.metadata().extensions().get("artifactCoordinates");
            for (BindingPlanEntry entry : compiled.bindingPlan().entries()) {
                if (entry.host().isPresent() && entry.artifactCoordinates().equals(coordinates)
                        && entry.capability().equals(child.binding().providerCapability())) {
                    providers.add(child.parties().provider().claimUri());
                }
            }
        }
        return providers;
    }

    /**
     * Builds and validates each hosted component's deployment configuration.
     *
     * @param providers the hosted providers' claim URIs
     * @param catalog   the scanned catalog entries, where each provider's packaged schema is read
     * @param flags     every {@code --component-config} value
     * @return the validated deployment configuration per provider claim URI
     * @throws IllegalArgumentException listing every problem: malformed flags, unknown components, a provider missing
     *                                  from the catalog, and every DCP configuration failure
     */
    public static Map<URI, Configuration> build(Set<URI> providers, List<CatalogEntry> catalog, List<String> flags) {
        List<String> problems = new ArrayList<>();
        Map<URI, Map<String, String>> given = parseFlags(flags, problems);
        for (URI named : given.keySet()) {
            if (!providers.contains(named)) problems.add("--component-config names " + named + ", which is not a hosted component of this contract");
        }
        Map<URI, Configuration> configurations = new TreeMap<>();
        for (URI provider : providers) {
            Optional<ComponentConfigurationSchema> schema;
            try {
                schema = schemaOf(provider, catalog);
            } catch (IOException | RuntimeException unreadable) {
                problems.add(provider + ": " + unreadable.getMessage());
                continue;
            }
            Map<String, String> raw = given.getOrDefault(provider, Map.of());
            Configuration configuration = configuration(raw, schema.orElse(null));
            if (schema.isPresent()) {
                for (ConfigFailure failure : ComponentConfigurationValidator.check(schema.get(), configuration)) {
                    problems.add(provider + " " + failure);
                }
            }
            configurations.put(provider, configuration);
        }
        if (!problems.isEmpty()) throw new IllegalArgumentException(String.join("; ", problems));
        return configurations;
    }

    /** Parses {@code <claimUri>#<key>=<value>}; a key given twice for one component is a problem. */
    private static Map<URI, Map<String, String>> parseFlags(List<String> flags, List<String> problems) {
        Map<URI, Map<String, String>> given = new LinkedHashMap<>();
        for (String flag : flags) {
            int hash = flag.indexOf('#');
            int equals = hash < 0 ? -1 : flag.indexOf('=', hash);
            if (hash <= 0 || equals <= hash + 1) {
                problems.add("--component-config must be <claimUri>#<key>=<value>: " + flag);
                continue;
            }
            URI component = URI.create(flag.substring(0, hash));
            String key = flag.substring(hash + 1, equals);
            if (given.computeIfAbsent(component, c -> new LinkedHashMap<>()).put(key, flag.substring(equals + 1)) != null) {
                problems.add("--component-config gives " + component + "#" + key + " more than once");
            }
        }
        return given;
    }

    /** Reads the packaged schema of the catalog entry whose claim is {@code provider}. */
    private static Optional<ComponentConfigurationSchema> schemaOf(URI provider, List<CatalogEntry> catalog) throws IOException {
        for (CatalogEntry entry : catalog) {
            if (entry.claimDescriptor().claim().identity().uri().equals(provider) && entry.localPath() != null) {
                return packagedSchema(entry.localPath());
            }
        }
        throw new IllegalArgumentException("the catalog has no jar for hosted component " + provider);
    }

    /** Splits flags into inline values (typed by the schema) and {@code config://} references. */
    private static Configuration configuration(Map<String, String> raw, ComponentConfigurationSchema schema) {
        Map<String, Object> values = new LinkedHashMap<>();
        Map<String, ConfigRef> references = new LinkedHashMap<>();
        raw.forEach((key, value) -> {
            if (value.startsWith("config://")) {
                references.put(key, new ConfigRef(value));
            } else {
                ConfigKey declared = schema == null ? null : schema.keys().get(key);
                values.put(key, declared == null ? value : typed(declared, value));
            }
        });
        return new Configuration(values, references);
    }

    /** Converts a CLI string to the key's declared type; a value that does not convert stays a string for DCP to refuse. */
    private static Object typed(ConfigKey key, String value) {
        if (!key.list()) return scalar(key.type(), value);
        List<Object> items = new ArrayList<>();
        for (String item : value.split(",")) items.add(scalar(key.type(), item.trim()));
        return items;
    }

    private static Object scalar(ConfigValueType type, String value) {
        if (type == ConfigValueType.INTEGER && value.matches("-?\\d{1,18}")) return Long.parseLong(value);
        if (type == ConfigValueType.BOOLEAN && (value.equals("true") || value.equals("false"))) return Boolean.parseBoolean(value);
        return value;
    }
}
