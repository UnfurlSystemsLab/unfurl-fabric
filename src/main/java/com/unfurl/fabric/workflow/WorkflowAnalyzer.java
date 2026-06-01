package com.unfurl.fabric.workflow;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.unfurl.fabric.needs.CapabilityRequirement;
import com.unfurl.fabric.needs.Need;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

public final class WorkflowAnalyzer {
    private final ObjectMapper mapper;

    public WorkflowAnalyzer() {
        ObjectMapper objectMapper = new ObjectMapper(new YAMLFactory());
        objectMapper.disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
        this.mapper = objectMapper;
    }

    public Need analyze(Path workflowPath) {
        try {
            WorkflowYamlDto workflow = mapper.readValue(workflowPath.toFile(), WorkflowYamlDto.class);
            return analyze(workflow);
        } catch (IOException ex) {
            throw new WorkflowAnalysisException("unable to read workflow " + workflowPath + ": " + ex.getMessage(), ex);
        }
    }

    Need analyze(WorkflowYamlDto workflow) {
        if (workflow == null) {
            throw new WorkflowAnalysisException("workflow is required");
        }
        TreeSet<String> capabilities = new TreeSet<>();
        for (WorkflowNodeDto node : workflow.nodes == null ? List.<WorkflowNodeDto>of() : workflow.nodes) {
            if (node != null && node.uses != null && !node.uses.isBlank()) {
                capabilities.add(node.uses);
            }
        }
        List<CapabilityRequirement> required = capabilities.stream()
                .map(capability -> CapabilityRequirement.requiredOf(capability, "*"))
                .toList();
        return new Need(required, List.of(), List.of(), Set.of(), null, Map.of());
    }

    static final class WorkflowYamlDto {
        public String id;
        public String version;
        public List<WorkflowNodeDto> nodes;
    }

    static final class WorkflowNodeDto {
        public String id;
        public String uses;
    }
}
