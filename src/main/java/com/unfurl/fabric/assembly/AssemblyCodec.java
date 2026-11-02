package com.unfurl.fabric.assembly;

import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.unfurl.dcp.contract.ContractCodec;
import java.io.IOException;

/** Codec Strategy: strict storage indexes and YAML inputs reuse DCP canonical serialization and reject duplicate keys. */
final class AssemblyCodec {
    static final ObjectMapper JSON = ContractCodec.canonicalMapper()
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .enable(com.fasterxml.jackson.core.JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
    static final ObjectMapper YAML = new ObjectMapper(YAMLFactory.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build())
            .registerModule(new JavaTimeModule()).enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

    /** Canonical serialization for signing indexes and comparing their authenticated representation. */
    static byte[] bytes(Object value) throws IOException { return JSON.writeValueAsBytes(value); }

    /** Non-instantiable Codec utility; mappers are configured once and shared read-only. */
    private AssemblyCodec() { }
}
