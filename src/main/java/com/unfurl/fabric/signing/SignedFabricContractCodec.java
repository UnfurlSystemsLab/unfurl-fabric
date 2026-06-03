package com.unfurl.fabric.signing;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.fasterxml.jackson.dataformat.yaml.YAMLGenerator;
import com.fasterxml.jackson.datatype.jdk8.Jdk8Module;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.unfurl.fabric.compiler.CompiledContract;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Symmetric YAML codec for {@link SignedFabricContract}. Used by {@code fabric sign} to write
 * the signed envelope and by {@code fabric verify} to read it back.
 *
 * <p>Signatures are base64-encoded in the YAML form so the file remains text-clean. The
 * {@link CompiledContract} body is emitted as a nested mapping rather than an opaque blob, so
 * the operator can read it. Verification recomputes the canonical bytes from {@code contract}
 * and asserts the hash + signature against the re-derived bytes — the YAML form is therefore
 * tamper-evident: any edit to the contract section invalidates both the stored hash and the
 * signature.
 */
public final class SignedFabricContractCodec {

    private final ObjectMapper yamlMapper;

    public SignedFabricContractCodec() {
        YAMLFactory yamlFactory = new YAMLFactory()
                .disable(YAMLGenerator.Feature.WRITE_DOC_START_MARKER)
                .enable(YAMLGenerator.Feature.MINIMIZE_QUOTES);
        ObjectMapper mapper = new ObjectMapper(yamlFactory);
        mapper.registerModule(new Jdk8Module());
        mapper.registerModule(new JavaTimeModule());
        mapper.disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
        mapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        mapper.enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS);
        mapper.enable(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY);
        mapper.setSerializationInclusion(JsonInclude.Include.NON_NULL);
        this.yamlMapper = mapper;
    }

    public byte[] write(SignedFabricContract signed) {
        if (signed == null) {
            throw new FabricSigningException("signed contract is required");
        }
        try {
            Map<String, Object> envelope = new LinkedHashMap<>();
            envelope.put("canonicalHash", signed.canonicalHash());
            envelope.put("algorithm", signed.algorithm());
            envelope.put("signerKeyFingerprint", signed.signerKeyFingerprint());
            envelope.put("signature", Base64.getEncoder().encodeToString(signed.signature()));
            envelope.put("contract", signed.contract());
            return yamlMapper.writeValueAsBytes(envelope);
        } catch (IOException ex) {
            throw new FabricSigningException("unable to write signed contract: " + ex.getMessage(), ex);
        }
    }

    public SignedFabricContract parse(byte[] bytes) {
        if (bytes == null || bytes.length == 0) {
            throw new FabricSigningException("signed contract bytes are required");
        }
        try {
            SignedFabricContractEnvelope envelope =
                    yamlMapper.readValue(bytes, SignedFabricContractEnvelope.class);
            if (envelope.contract == null) {
                throw new FabricSigningException("signed contract is missing the `contract` block");
            }
            if (envelope.signature == null || envelope.signature.isBlank()) {
                throw new FabricSigningException("signed contract is missing `signature`");
            }
            byte[] signatureBytes = Base64.getDecoder().decode(envelope.signature);
            return new SignedFabricContract(
                    envelope.contract,
                    envelope.canonicalHash,
                    signatureBytes,
                    envelope.signerKeyFingerprint,
                    envelope.algorithm);
        } catch (IOException ex) {
            throw new FabricSigningException("unable to parse signed contract: " + ex.getMessage(), ex);
        } catch (IllegalArgumentException ex) {
            throw new FabricSigningException("signature is not valid base64: " + ex.getMessage(), ex);
        }
    }

    public java.nio.charset.Charset charset() {
        return StandardCharsets.UTF_8;
    }

    /**
     * Jackson DTO for the envelope shape. Public-visible fields keep Jackson reflection simple.
     */
    static final class SignedFabricContractEnvelope {
        public String canonicalHash;
        public String algorithm;
        public String signerKeyFingerprint;
        public String signature;
        public CompiledContract contract;
    }
}
