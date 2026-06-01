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
 */
public final class NeedsCodec {

    private final ObjectMapper mapper;

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

    public Need read(Path needsFile) {
        try {
            NeedsEnvelope envelope = mapper.readValue(needsFile.toFile(), NeedsEnvelope.class);
            return toNeed(envelope);
        } catch (IOException ex) {
            throw new NeedsException("unable to read needs file " + needsFile + ": " + ex.getMessage(), ex);
        }
    }

    public Need parse(byte[] bytes) {
        try {
            NeedsEnvelope envelope = mapper.readValue(bytes, NeedsEnvelope.class);
            return toNeed(envelope);
        } catch (IOException ex) {
            throw new NeedsException("unable to parse needs: " + ex.getMessage(), ex);
        }
    }

    public void write(Need need, Path target) {
        try {
            Files.createDirectories(target.toAbsolutePath().getParent());
            mapper.writerWithDefaultPrettyPrinter().writeValue(target.toFile(), toEnvelope(need));
        } catch (IOException ex) {
            throw new NeedsException("unable to write needs file " + target + ": " + ex.getMessage(), ex);
        }
    }

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

    static final class NeedsEnvelope {
        public List<CapabilityRequirementCarrier> requiredCapabilities;
        public List<CapabilityRequirementCarrier> optionalCapabilities;
        public List<ArtifactConstraintCarrier> artifactConstraints;
        public List<String> refusalExpectations;
        public String trustPolicyRef;
        public Map<String, String> bindingPreferences;
    }

    static final class CapabilityRequirementCarrier {
        public String capability;
        public String capabilityVersion;
    }

    static final class ArtifactConstraintCarrier {
        public String group;
        public String name;
        public String version;
    }
}
