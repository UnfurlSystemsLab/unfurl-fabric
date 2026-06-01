package com.unfurl.fabric.compiler;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.fasterxml.jackson.dataformat.yaml.YAMLGenerator;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;

public final class CompiledContractCodec {
    private final ObjectMapper yamlMapper = buildYamlMapper(false);
    private final ObjectMapper canonicalMapper = buildYamlMapper(true);

    public CompiledContract parse(byte[] bytes) {
        try {
            return yamlMapper.readValue(bytes, CompiledContract.class);
        } catch (IOException ex) {
            throw new ContractCompileException("unable to parse compiled contract: " + ex.getMessage(), ex);
        }
    }

    public byte[] write(CompiledContract compiled) {
        try {
            return yamlMapper.writeValueAsBytes(compiled);
        } catch (IOException ex) {
            throw new ContractCompileException("unable to write compiled contract: " + ex.getMessage(), ex);
        }
    }

    public byte[] canonicalBytes(CompiledContract compiled) {
        try {
            return canonicalMapper.writeValueAsBytes(compiled);
        } catch (IOException ex) {
            throw new ContractCompileException("unable to canonicalize compiled contract: " + ex.getMessage(), ex);
        }
    }

    public Charset charset() {
        return StandardCharsets.UTF_8;
    }

    private static ObjectMapper buildYamlMapper(boolean canonical) {
        YAMLFactory yamlFactory = new YAMLFactory()
                .disable(YAMLGenerator.Feature.WRITE_DOC_START_MARKER)
                .disable(YAMLGenerator.Feature.SPLIT_LINES);
        if (!canonical) {
            yamlFactory.enable(YAMLGenerator.Feature.MINIMIZE_QUOTES);
        }
        ObjectMapper mapper = new ObjectMapper(yamlFactory);
        mapper.registerModule(new JavaTimeModule());
        mapper.disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
        mapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        mapper.enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS);
        mapper.enable(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY);
        mapper.setSerializationInclusion(JsonInclude.Include.NON_NULL);
        return mapper;
    }
}
