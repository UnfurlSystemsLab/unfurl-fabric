package com.unfurl.fabric.needs;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.fasterxml.jackson.dataformat.yaml.YAMLGenerator;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.unfurl.substrate.api.BindingMode;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Reads operator-authored {@code needs.yaml}. Provider-friendly schema:
 *
 * <pre>
 * requiredCapabilities:
 *   - capability: storage.put
 *     capabilityVersion: "^1"
 * optionalCapabilities:
 *   - capability: audit.write
 *     capabilityVersion: "^1"
 * artifactConstraints:
 *   - group: com.unfurl
 *     name: unfurl-storage-s3
 *     version: ">=1.2.0 &lt;2.0.0"
 * refusalExpectations: [identity-management]
 * trustPolicyRef: ./trust-policy.yaml
 * bindingPreferences:
 *   storage.put: IN_PROCESS
 * </pre>
 *
 * <p>Pattern: <b>Codec/DTO mapper</b> with an internal Jackson-bound <b>envelope</b> ({@link NeedsEnvelope}
 * and carriers) decoupling the wire schema from the immutable domain {@link Need}. The mapper is
 * configured once and reused (stateless, thread-safe).
 */
public final class NeedsCodec {

    /** Pre-configured YAML object mapper (lenient on unknown fields, NON_NULL output). */
    private final ObjectMapper mapper;

    /** Construct a codec with a YAML mapper tuned for readable, forward-compatible needs documents. */
    public NeedsCodec() {
        YAMLFactory yamlFactory = new YAMLFactory()
                .disable(YAMLGenerator.Feature.WRITE_DOC_START_MARKER)
                .enable(YAMLGenerator.Feature.MINIMIZE_QUOTES);
        ObjectMapper m = new ObjectMapper(yamlFactory);
        m.registerModule(new JavaTimeModule());
        m.disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
        m.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        m.setSerializationInclusion(JsonInclude.Include.NON_NULL);
        this.mapper = m;
    }

    /**
     * Read and decode a needs document from a file.
     *
     * @param needsFile path to the {@code needs.yaml} file.
     * @return the decoded immutable {@link Need}.
     * @throws NeedsException if the file cannot be read or parsed.
     */
    public Need read(Path needsFile) {
        try {
            NeedsEnvelope envelope = mapper.readValue(needsFile.toFile(), NeedsEnvelope.class);
            return toNeed(envelope);
        } catch (IOException ex) {
            throw new NeedsException("unable to read needs file " + needsFile + ": " + ex.getMessage(), ex);
        }
    }

    /**
     * Decode a needs document from in-memory bytes.
     *
     * @param bytes the raw YAML/JSON bytes.
     * @return the decoded immutable {@link Need}.
     * @throws NeedsException if the bytes cannot be parsed.
     */
    public Need parse(byte[] bytes) {
        try {
            NeedsEnvelope envelope = mapper.readValue(bytes, NeedsEnvelope.class);
            return toNeed(envelope);
        } catch (IOException ex) {
            throw new NeedsException("unable to parse needs: " + ex.getMessage(), ex);
        }
    }

    /**
     * Encode a {@link Need} to a YAML file, creating parent directories as needed.
     *
     * @param need   the need to serialize.
     * @param target the output file path.
     * @throws NeedsException if the file cannot be written.
     */
    public void write(Need need, Path target) {
        try {
            Files.createDirectories(target.toAbsolutePath().getParent());
            mapper.writerWithDefaultPrettyPrinter().writeValue(target.toFile(), toEnvelope(need));
        } catch (IOException ex) {
            throw new NeedsException("unable to write needs file " + target + ": " + ex.getMessage(), ex);
        }
    }

    /**
     * Encode a {@link Need} as YAML text for API responses and diagnostic artifacts.
     *
     * @param need the need to serialize.
     * @return YAML text using the same provider-friendly schema as {@link #write(Need, Path)}.
     * @throws NeedsException if the need cannot be rendered.
     */
    public String writeToString(Need need) {
        try {
            return mapper.writerWithDefaultPrettyPrinter().writeValueAsString(toEnvelope(need));
        } catch (IOException ex) {
            throw new NeedsException("unable to render needs: " + ex.getMessage(), ex);
        }
    }

    /**
     * Map a decoded wire {@link NeedsEnvelope} into the immutable domain {@link Need}.
     *
     * @param envelope the parsed envelope.
     * @return the domain need.
     */
    private Need toNeed(NeedsEnvelope envelope) {
        List<CapabilityRequirement> required = toRequirements(envelope.requiredCapabilities, true);
        List<CapabilityRequirement> optional = toRequirements(envelope.optionalCapabilities, false);
        List<ArtifactConstraint> constraints = new ArrayList<>();
        if (envelope.artifactConstraints != null) {
            for (ArtifactConstraintCarrier c : envelope.artifactConstraints) {
                constraints.add(new ArtifactConstraint(c.group, c.name,
                        new ArtifactVersionRange(c.version == null ? "*" : c.version)));
            }
        }
        Set<String> refusals = envelope.refusalExpectations == null
                ? Set.of() : new LinkedHashSet<>(envelope.refusalExpectations);
        Map<String, BindingMode> bindingPrefs = new LinkedHashMap<>();
        if (envelope.bindingPreferences != null) {
            envelope.bindingPreferences.forEach((cap, mode) -> bindingPrefs.put(cap, BindingMode.valueOf(mode)));
        }
        Path trustPolicy = envelope.trustPolicyRef == null
                ? null : Paths.get(envelope.trustPolicyRef);
        return new Need(required, optional, constraints, refusals, trustPolicy, bindingPrefs);
    }

    /**
     * Convert raw capability carriers into domain {@link CapabilityRequirement}s, defaulting blank
     * ranges to "any".
     *
     * @param raw      the wire carriers (may be null).
     * @param required whether these are required (true) or optional (false) requirements.
     * @return the domain requirements (empty when raw is null).
     */
    private List<CapabilityRequirement> toRequirements(List<CapabilityRequirementCarrier> raw, boolean required) {
        if (raw == null) {
            return List.of();
        }
        List<CapabilityRequirement> out = new ArrayList<>();
        for (CapabilityRequirementCarrier c : raw) {
            String range = c.capabilityVersion == null || c.capabilityVersion.isBlank() ? "*" : c.capabilityVersion;
            out.add(new CapabilityRequirement(c.capability, new CapabilityVersionRange(range), required));
        }
        return out;
    }

    /**
     * Map a domain {@link Need} back into the serializable wire {@link NeedsEnvelope}.
     *
     * @param need the domain need.
     * @return the wire envelope.
     */
    private NeedsEnvelope toEnvelope(Need need) {
        NeedsEnvelope envelope = new NeedsEnvelope();
        envelope.requiredCapabilities = toCarriers(need.requiredCapabilities());
        envelope.optionalCapabilities = toCarriers(need.optionalCapabilities());
        envelope.artifactConstraints = new ArrayList<>();
        for (ArtifactConstraint c : need.artifactConstraints()) {
            ArtifactConstraintCarrier carrier = new ArtifactConstraintCarrier();
            carrier.group = c.group();
            carrier.name = c.name();
            carrier.version = c.version().range();
            envelope.artifactConstraints.add(carrier);
        }
        envelope.refusalExpectations = new ArrayList<>(need.refusalExpectations());
        envelope.trustPolicyRef = need.trustPolicyRef() == null ? null : need.trustPolicyRef().toString();
        envelope.bindingPreferences = new LinkedHashMap<>();
        need.bindingPreferences().forEach((cap, mode) -> envelope.bindingPreferences.put(cap, mode.name()));
        return envelope;
    }

    /**
     * Convert domain requirements into wire carriers.
     *
     * @param reqs the domain requirements.
     * @return the wire carriers.
     */
    private List<CapabilityRequirementCarrier> toCarriers(List<CapabilityRequirement> reqs) {
        List<CapabilityRequirementCarrier> out = new ArrayList<>();
        for (CapabilityRequirement r : reqs) {
            CapabilityRequirementCarrier carrier = new CapabilityRequirementCarrier();
            carrier.capability = r.capability();
            carrier.capabilityVersion = r.capabilityVersion().range();
            out.add(carrier);
        }
        return out;
    }

    /** Jackson-bound wire shape of a needs document (DTO; fields map 1:1 to the YAML schema). */
    static final class NeedsEnvelope {
        /** Required capability carriers. */
        public List<CapabilityRequirementCarrier> requiredCapabilities;
        /** Optional capability carriers. */
        public List<CapabilityRequirementCarrier> optionalCapabilities;
        /** Artifact pin carriers. */
        public List<ArtifactConstraintCarrier> artifactConstraints;
        /** Concern names the operator expects to be refused. */
        public List<String> refusalExpectations;
        /** Optional trust-policy document path. */
        public String trustPolicyRef;
        /** Capability → binding-mode-name preferences. */
        public Map<String, String> bindingPreferences;
    }

    /** Jackson-bound wire shape of one capability requirement. */
    static final class CapabilityRequirementCarrier {
        /** Capability name. */
        public String capability;
        /** Capability version range (npm-style); blank means any. */
        public String capabilityVersion;
    }

    /** Jackson-bound wire shape of one artifact constraint. */
    static final class ArtifactConstraintCarrier {
        /** Artifact group. */
        public String group;
        /** Artifact name. */
        public String name;
        /** Artifact version range; null means any. */
        public String version;
    }
}
