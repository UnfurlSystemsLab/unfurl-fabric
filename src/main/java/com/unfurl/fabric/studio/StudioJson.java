package com.unfurl.fabric.studio;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jdk8.Jdk8Module;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

/**
 * Factory for the JSON mapper every Studio HTTP handler uses.
 */
public final class StudioJson {
    /** Largest decoded artifact a catalog admission accepts in {@code artifactBase64}. */
    public static final int MAX_ARTIFACT_BYTES = 96 * 1024 * 1024;
    /**
     * Largest JSON string a request may carry: the base64 length of {@link #MAX_ARTIFACT_BYTES}. Admission takes the
     * component's jar bytes, never a detached claim (DCP HLD-C §3.10), so a product jar must fit; a longer string is
     * refused as malformed JSON rather than truncated.
     */
    public static final int MAX_STRING_LENGTH = ((MAX_ARTIFACT_BYTES + 2) / 3) * 4;

    /**
     * Builds the Studio mapper: lenient on unknown fields, ISO dates, no nulls in output, and a string limit large
     * enough for a jar in {@code artifactBase64}.
     *
     * @return a new mapper.
     */
    public static ObjectMapper mapper() {
        ObjectMapper mapper = new ObjectMapper();
        mapper.getFactory().setStreamReadConstraints(StreamReadConstraints.builder()
                .maxStringLength(MAX_STRING_LENGTH)
                .build());
        mapper.registerModule(new JavaTimeModule());
        mapper.registerModule(new Jdk8Module());
        mapper.disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
        mapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        mapper.setSerializationInclusion(JsonInclude.Include.NON_NULL);
        return mapper;
    }

    private StudioJson() {
    }
}
