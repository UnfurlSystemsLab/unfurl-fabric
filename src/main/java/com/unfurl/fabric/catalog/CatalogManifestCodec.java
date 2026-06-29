package com.unfurl.fabric.catalog;

import com.unfurl.fabric.artifact.ArtifactDescriptor;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.fasterxml.jackson.dataformat.yaml.YAMLGenerator;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.fasterxml.jackson.datatype.jdk8.Jdk8Module;
import com.unfurl.dcp.claim.Claim;
import com.unfurl.deployment.domain.ComponentShapeProfile;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * Reads META-INF/unfurl-catalog.yaml — the two-block manifest separating a pure DCP claim
 * from catalog-only metadata (lifecycle, artifact, binding). Provides canonical claim-hash
 * computation. Used by CatalogScanner; never executes catalog JAR code.
 *
 * <p>Pattern: <b>Codec/DTO mapper</b> with two purpose-built Jackson mappers — a lenient reader for
 * manifests and a strict <b>canonical</b> mapper (sorted keys/properties) for reproducible claim hashing.
 * Internal {@link ManifestEnvelope}/{@link CatalogBlock} DTOs decouple the wire schema from domain types.
 */
public final class CatalogManifestCodec {

    /** Lenient YAML mapper for reading manifests (ignores unknown fields). */
    private final ObjectMapper yamlMapper;
    /** Strict, deterministic mapper used only for canonical claim serialization/hashing. */
    private final ObjectMapper canonicalClaimMapper;

    /** Construct the codec, building both the lenient and canonical mappers once. */
    public CatalogManifestCodec() {
        this.yamlMapper = buildYamlMapper();
        this.canonicalClaimMapper = buildCanonicalClaimMapper();
    }

    /**
     * Parses the bytes of META-INF/unfurl-catalog.yaml into a {@link ParsedManifest}.
     * Throws CatalogScanException if the file is malformed, missing the required blocks,
     * or violates a required-field constraint.
     *
     * @param manifestBytes the raw manifest bytes.
     * @return the parsed manifest (claim + catalog blocks).
     * @throws CatalogScanException if empty, malformed, or missing a required block.
     */
    public ParsedManifest parse(byte[] manifestBytes) {
        if (manifestBytes == null || manifestBytes.length == 0) {
            throw new CatalogScanException("empty manifest bytes");
        }
        try {
            ManifestEnvelope envelope = yamlMapper.readValue(manifestBytes, ManifestEnvelope.class);
            if (envelope.claim == null) {
                throw new CatalogScanException("manifest is missing the required `claim` block");
            }
            if (envelope.catalog == null) {
                throw new CatalogScanException("manifest is missing the required `catalog` block");
            }
            CatalogBlock cat = envelope.catalog;
            if (cat.lifecycle == null) {
                throw new CatalogScanException("manifest catalog block is missing required `lifecycle`");
            }
            if (cat.artifact == null) {
                throw new CatalogScanException("manifest catalog block is missing required `artifact`");
            }
            if (cat.binding == null) {
                throw new CatalogScanException("manifest catalog block is missing required `binding`");
            }
            return new ParsedManifest(envelope.claim, cat.lifecycle, cat.artifact, cat.binding, cat.componentShapeProfile);
        } catch (IOException ex) {
            throw new CatalogScanException("unable to parse catalog manifest: " + ex.getMessage(), ex);
        }
    }

    /**
     * Computes the canonical claim hash used by the catalog and the compiled contract to pin
     * the exact claim bytes the planner observed. Serialization uses the canonical claim mapper
     * to guarantee determinism (sorted properties, no flow-style aliasing, UTF-8).
     *
     * @param claim the claim to hash.
     * @return the lowercase hex SHA-256 of the canonical claim bytes.
     * @throws CatalogScanException if the claim cannot be canonicalized.
     */
    public String computeClaimHash(Claim claim) {
        try {
            byte[] canonical = canonicalClaimMapper.writeValueAsBytes(claim);
            return Sha256.hexLower(canonical);
        } catch (IOException ex) {
            throw new CatalogScanException("unable to canonicalize claim: " + ex.getMessage(), ex);
        }
    }

    /**
     * Build the lenient YAML mapper for reading manifests (unknown fields ignored, NON_NULL output).
     *
     * @return the configured mapper.
     */
    private static ObjectMapper buildYamlMapper() {
        YAMLFactory yamlFactory = new YAMLFactory()
                .disable(YAMLGenerator.Feature.WRITE_DOC_START_MARKER)
                .enable(YAMLGenerator.Feature.MINIMIZE_QUOTES);
        ObjectMapper mapper = new ObjectMapper(yamlFactory);
        mapper.registerModule(new Jdk8Module());
        mapper.registerModule(new JavaTimeModule());
        mapper.disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
        mapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        mapper.setSerializationInclusion(JsonInclude.Include.NON_NULL);
        return mapper;
    }

    /**
     * Build the strict canonical mapper for claim hashing: map entries and properties sorted
     * alphabetically and line splitting disabled, so identical claims always serialize identically.
     *
     * @return the configured canonical mapper.
     */
    private static ObjectMapper buildCanonicalClaimMapper() {
        YAMLFactory yamlFactory = new YAMLFactory()
                .disable(YAMLGenerator.Feature.WRITE_DOC_START_MARKER)
                .disable(YAMLGenerator.Feature.SPLIT_LINES);
        ObjectMapper mapper = new ObjectMapper(yamlFactory);
        mapper.registerModule(new Jdk8Module());
        mapper.registerModule(new JavaTimeModule());
        mapper.enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS);
        mapper.enable(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY);
        mapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        mapper.setSerializationInclusion(JsonInclude.Include.NON_NULL);
        return mapper;
    }

    /**
     * Internal Jackson envelope mirroring the two-block YAML manifest. Held package-private
     * so the codec is the single point of (de)serialization for the manifest format.
     */
    static final class ManifestEnvelope {
        /** The pure DCP claim block. */
        public Claim claim;
        /** The catalog-only metadata block. */
        public CatalogBlock catalog;
    }

    /**
     * Catalog metadata as authored in the manifest: lifecycle + artifact (authored) + binding.
     * Distinct from the runtime {@link CatalogMetadata} which carries only the runtime-relevant
     * pair (lifecycle + binding); the authored artifact is enriched by the scanner with a
     * computed SHA-256 to become the runtime {@link ArtifactDescriptor}.
     */
    static final class CatalogBlock {
        /** Authored lifecycle block. */
        public Lifecycle lifecycle;
        /** Authored artifact block (pre-SHA enrichment). */
        public AuthoredArtifact artifact;
        /** Authored binding block. */
        public BindingDescriptor binding;
        /** Optional deployment shape profile. */
        public ComponentShapeProfile componentShapeProfile;
    }

    /**
     * Small internal SHA-256 hex utility (non-instantiable).
     *
     * <p>Pattern: private <b>utility class</b>.
     */
    private static final class Sha256 {
        /**
         * Lowercase hex SHA-256 of the given bytes.
         *
         * @param data the input bytes.
         * @return the 64-char lowercase hex digest.
         * @throws IllegalStateException if SHA-256 is unavailable on the JVM.
         */
        static String hexLower(byte[] data) {
            try {
                java.security.MessageDigest digest = java.security.MessageDigest.getInstance("SHA-256");
                byte[] hash = digest.digest(data);
                StringBuilder hex = new StringBuilder(hash.length * 2);
                for (byte b : hash) {
                    hex.append(String.format("%02x", b));
                }
                return hex.toString();
            } catch (java.security.NoSuchAlgorithmException ex) {
                throw new IllegalStateException("SHA-256 not available on this JVM", ex);
            }
        }

        /** Non-instantiable. */
        private Sha256() {
        }
    }

    /**
     * Exposes the canonical hash computation for callers (e.g., the scanner) that have raw
     * bytes (not a Claim object).
     *
     * @param data the input bytes (null treated as empty).
     * @return the lowercase hex SHA-256.
     */
    public static String sha256Hex(byte[] data) {
        if (data == null) {
            data = new byte[0];
        }
        return Sha256.hexLower(data);
    }

    /**
     * UTF-8 canonical bytes of a Claim — used both for hashing and for direct byte comparison.
     *
     * @param claim the claim to canonicalize.
     * @return the canonical UTF-8 bytes.
     * @throws CatalogScanException if the claim cannot be canonicalized.
     */
    public byte[] canonicalClaimBytes(Claim claim) {
        try {
            return canonicalClaimMapper.writeValueAsBytes(claim);
        } catch (IOException ex) {
            throw new CatalogScanException("unable to canonicalize claim: " + ex.getMessage(), ex);
        }
    }

    /**
     * Charset used by canonical claim bytes. Exposed for diagnostic rendering.
     *
     * @return UTF-8.
     */
    public java.nio.charset.Charset charset() {
        return StandardCharsets.UTF_8;
    }
}
