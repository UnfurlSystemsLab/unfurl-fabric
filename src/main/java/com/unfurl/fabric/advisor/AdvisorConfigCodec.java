package com.unfurl.fabric.advisor;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.fasterxml.jackson.dataformat.yaml.YAMLGenerator;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * YAML codec for {@link AdvisorConfig}. The on-disk schema:
 *
 * <pre>
 * provider: ollama
 * model: my-org/composition-advisor:1.0
 * endpoint: http://localhost:11434/api/generate    # optional
 * apiKey: null                                     # optional
 * effortOverrides:                                 # optional, per-purpose
 *   rank-ambiguous-candidates: OFF
 *   suggest-substitutes-for-no-match: HIGH
 *   explain: MEDIUM
 * providerOptions:                                 # optional, provider-specific
 *   ollama.temperature: "0.0"
 *   ollama.seed: "42"
 * </pre>
 */
public final class AdvisorConfigCodec {

    private final ObjectMapper yaml;

    public AdvisorConfigCodec() {
        YAMLFactory factory = new YAMLFactory()
                .disable(YAMLGenerator.Feature.WRITE_DOC_START_MARKER)
                .enable(YAMLGenerator.Feature.MINIMIZE_QUOTES);
        ObjectMapper mapper = new ObjectMapper(factory);
        mapper.disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
        mapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        mapper.setSerializationInclusion(JsonInclude.Include.NON_NULL);
        this.yaml = mapper;
    }

    public AdvisorConfig read(Path path) {
        try {
            return parse(Files.readAllBytes(path));
        } catch (IOException ex) {
            throw new AdvisorBootstrapException(
                    "unable to read advisor config " + path + ": " + ex.getMessage(), ex);
        }
    }

    public AdvisorConfig parse(byte[] bytes) {
        if (bytes == null || bytes.length == 0) {
            throw new AdvisorBootstrapException("advisor config bytes are empty");
        }
        try {
            Envelope envelope = yaml.readValue(bytes, Envelope.class);
            return toConfig(envelope);
        } catch (IOException ex) {
            throw new AdvisorBootstrapException(
                    "unable to parse advisor config: " + ex.getMessage(), ex);
        }
    }

    public void write(AdvisorConfig config, Path target) {
        try {
            Files.createDirectories(target.toAbsolutePath().getParent());
            yaml.writerWithDefaultPrettyPrinter().writeValue(target.toFile(), toEnvelope(config));
        } catch (IOException ex) {
            throw new AdvisorBootstrapException(
                    "unable to write advisor config " + target + ": " + ex.getMessage(), ex);
        }
    }

    private AdvisorConfig toConfig(Envelope envelope) {
        if (envelope.provider == null || envelope.provider.isBlank()) {
            throw new AdvisorBootstrapException("advisor config is missing required `provider`");
        }
        if (envelope.model == null || envelope.model.isBlank()) {
            throw new AdvisorBootstrapException("advisor config is missing required `model`");
        }
        Map<String, ThinkingEffort> efforts = new LinkedHashMap<>();
        if (envelope.effortOverrides != null) {
            envelope.effortOverrides.forEach((purpose, value) -> {
                if (value == null || value.isBlank()) {
                    return;
                }
                try {
                    efforts.put(purpose, ThinkingEffort.valueOf(value.toUpperCase()));
                } catch (IllegalArgumentException ex) {
                    throw new AdvisorBootstrapException(
                            "effortOverrides." + purpose + ": '" + value
                                    + "' is not a valid ThinkingEffort (OFF/LOW/MEDIUM/HIGH)");
                }
            });
        }
        Map<String, String> options = envelope.providerOptions == null
                ? Map.of()
                : envelope.providerOptions;
        return new AdvisorConfig(
                envelope.provider,
                envelope.model,
                envelope.endpoint,
                envelope.apiKey,
                efforts,
                options);
    }

    private Envelope toEnvelope(AdvisorConfig config) {
        Envelope envelope = new Envelope();
        envelope.provider = config.providerName();
        envelope.model = config.model();
        envelope.endpoint = config.endpoint();
        envelope.apiKey = config.apiKey();
        envelope.effortOverrides = new LinkedHashMap<>();
        config.effortOverrides().forEach((purpose, effort) ->
                envelope.effortOverrides.put(purpose, effort.name()));
        envelope.providerOptions = config.providerOptions().isEmpty()
                ? null
                : new LinkedHashMap<>(config.providerOptions());
        return envelope;
    }

    static final class Envelope {
        public String provider;
        public String model;
        public String endpoint;
        public String apiKey;
        public Map<String, String> effortOverrides;
        public Map<String, String> providerOptions;
    }
}
