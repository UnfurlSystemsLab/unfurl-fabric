package com.unfurl.fabric.advisor;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.ServiceLoader;

/**
 * Resolves an {@link AiAdvisor} from operator inputs without fabric core importing any
 * adapter class.
 *
 * <p>Provider discovery uses {@link ServiceLoader} over {@link ProviderFactory}. Each
 * adapter ships a {@code META-INF/services/com.unfurl.fabric.advisor.ProviderFactory}
 * file. When fabric core can't find a factory matching the configured provider name, it
 * throws {@link AdvisorBootstrapException} with a clear message naming the missing
 * Maven module — the operator's signal to add the adapter to the build.
 *
 * <h2>Configuration precedence</h2>
 * In order of priority (highest first):
 * <ol>
 *   <li><b>CLI flags</b> — explicit invocation-time overrides</li>
 *   <li><b>Environment variables</b> — typical for containerized deployments</li>
 *   <li><b>Config file</b> — operator-curated, version-controlled defaults</li>
 *   <li><b>Built-in default</b> — {@link NoopAiAdvisor} (fully offline, deterministic)</li>
 * </ol>
 *
 * <p>If no provider is named at any level, the bootstrap returns {@link NoopAiAdvisor}.
 * Fabric stays air-gappable by default; AI is strictly opt-in.
 */
public final class AdvisorBootstrap {

    /**
     * Environment variable names recognized at the env-var precedence level.
     * Operators can prefix the env-var names if their deployment requires it.
     */
    public static final class EnvVar {
        public static final String PROVIDER = "FABRIC_ADVISOR_PROVIDER";
        public static final String MODEL = "FABRIC_ADVISOR_MODEL";
        public static final String ENDPOINT = "FABRIC_ADVISOR_ENDPOINT";
        public static final String API_KEY = "FABRIC_ADVISOR_API_KEY";
        public static final String CONFIG_FILE = "FABRIC_ADVISOR_CONFIG";

        private EnvVar() {
        }
    }

    /**
     * CLI flag names recognized at the highest precedence level. These mirror the env-var
     * keys but are passed as {@code --advisor-X} arguments parsed by the CLI layer.
     */
    public static final class CliFlag {
        public static final String PROVIDER = "advisor-provider";
        public static final String MODEL = "advisor-model";
        public static final String ENDPOINT = "advisor-endpoint";
        public static final String API_KEY = "advisor-api-key";
        public static final String CONFIG_FILE = "advisor-config";

        private CliFlag() {
        }
    }

    private AdvisorBootstrap() {
    }

    public static AiAdvisor resolve(BootstrapInputs inputs) {
        AdvisorConfig config = resolveConfig(inputs);
        if (config == null) {
            return new NoopAiAdvisor();
        }
        LlmProvider provider = findProvider(config);
        return new LlmAdvisor(provider);
    }

    /**
     * Test seam: resolves config without instantiating any provider. Production code
     * uses {@link #resolve(BootstrapInputs)}.
     */
    public static AdvisorConfig resolveConfig(BootstrapInputs inputs) {
        BootstrapInputs effective = inputs == null
                ? new BootstrapInputs(null, Map.of(), Map.of(), AdvisorConfigCodec::new)
                : inputs;
        Map<String, String> resolved = new LinkedHashMap<>();

        // Lowest precedence: config file (if any)
        Path configFile = pick(effective, CliFlag.CONFIG_FILE, EnvVar.CONFIG_FILE) != null
                ? Path.of(pick(effective, CliFlag.CONFIG_FILE, EnvVar.CONFIG_FILE))
                : effective.configFile();
        AdvisorConfig fromFile = configFile != null
                ? effective.codecSupplier().get().read(configFile)
                : null;
        if (fromFile != null) {
            resolved.put(CliFlag.PROVIDER, fromFile.providerName());
            resolved.put(CliFlag.MODEL, fromFile.model());
            if (fromFile.endpoint() != null) {
                resolved.put(CliFlag.ENDPOINT, fromFile.endpoint());
            }
            if (fromFile.apiKey() != null) {
                resolved.put(CliFlag.API_KEY, fromFile.apiKey());
            }
        }

        // Middle precedence: env vars
        merge(resolved, EnvVar.PROVIDER, CliFlag.PROVIDER, effective.envVars());
        merge(resolved, EnvVar.MODEL, CliFlag.MODEL, effective.envVars());
        merge(resolved, EnvVar.ENDPOINT, CliFlag.ENDPOINT, effective.envVars());
        merge(resolved, EnvVar.API_KEY, CliFlag.API_KEY, effective.envVars());

        // Highest precedence: CLI flags
        merge(resolved, CliFlag.PROVIDER, CliFlag.PROVIDER, effective.cliFlags());
        merge(resolved, CliFlag.MODEL, CliFlag.MODEL, effective.cliFlags());
        merge(resolved, CliFlag.ENDPOINT, CliFlag.ENDPOINT, effective.cliFlags());
        merge(resolved, CliFlag.API_KEY, CliFlag.API_KEY, effective.cliFlags());

        String provider = resolved.get(CliFlag.PROVIDER);
        String model = resolved.get(CliFlag.MODEL);
        if (provider == null || provider.isBlank() || model == null || model.isBlank()) {
            // No provider configured at any level — fall through to Noop default
            return null;
        }

        Map<String, ThinkingEffort> efforts = fromFile == null ? Map.of() : fromFile.effortOverrides();
        Map<String, String> options = fromFile == null ? Map.of() : fromFile.providerOptions();

        return new AdvisorConfig(provider, model, resolved.get(CliFlag.ENDPOINT),
                resolved.get(CliFlag.API_KEY), efforts, options);
    }

    private static LlmProvider findProvider(AdvisorConfig config) {
        ServiceLoader<ProviderFactory> loader = ServiceLoader.load(ProviderFactory.class);
        List<String> available = new java.util.ArrayList<>();
        for (ProviderFactory factory : loader) {
            available.add(factory.providerName());
            if (factory.providerName().equals(config.providerName())) {
                return factory.create(config);
            }
        }
        throw new AdvisorBootstrapException(
                "no provider adapter on classpath for '" + config.providerName() + "'. "
                        + "Add the corresponding adapter module "
                        + "(e.g. com.unfurl.fabric.advisor:unfurl-fabric-advisor-"
                        + config.providerName() + ") to your build. "
                        + "Adapters discovered on this classpath: "
                        + (available.isEmpty() ? "<none>" : String.join(", ", available)));
    }

    private static String pick(BootstrapInputs inputs, String cliFlag, String envVar) {
        String fromCli = inputs.cliFlags().get(cliFlag);
        if (fromCli != null && !fromCli.isBlank()) {
            return fromCli;
        }
        String fromEnv = inputs.envVars().get(envVar);
        if (fromEnv != null && !fromEnv.isBlank()) {
            return fromEnv;
        }
        return null;
    }

    private static void merge(Map<String, String> resolved, String sourceKey, String targetKey,
                              Map<String, String> source) {
        String value = source.get(sourceKey);
        if (value != null && !value.isBlank()) {
            resolved.put(targetKey, value);
        }
    }

    public record BootstrapInputs(
            Path configFile,
            Map<String, String> cliFlags,
            Map<String, String> envVars,
            java.util.function.Supplier<AdvisorConfigCodec> codecSupplier
    ) {
        public BootstrapInputs {
            cliFlags = cliFlags == null ? Map.of() : Map.copyOf(cliFlags);
            envVars = envVars == null ? Map.of() : Map.copyOf(envVars);
            codecSupplier = codecSupplier == null ? AdvisorConfigCodec::new : codecSupplier;
        }
    }
}
