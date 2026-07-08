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

/**
 * Analyzer Strategy: reads Flow workflow YAML and emits the DCP capabilities used by its nodes as
 * Fabric {@link Need} requirements.
 *
 * <p>Inputs are either a workflow file path or in-memory YAML content. Outputs are deterministic,
 * sorted required capabilities. Unknown workflow fields are ignored so newer Flow documents can be
 * analyzed by older Fabric builds; a missing workflow object remains an error.
 */
public final class WorkflowAnalyzer {
    private final ObjectMapper mapper;

    /** Constructor: configures the YAML mapper used by both file and in-memory analysis. */
    public WorkflowAnalyzer() {
        ObjectMapper objectMapper = new ObjectMapper(new YAMLFactory());
        objectMapper.disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
        this.mapper = objectMapper;
    }

    /**
     * Analyze a workflow YAML file.
     *
     * @param workflowPath path to the workflow YAML.
     * @return a suggested {@link Need} containing distinct node {@code uses} capabilities.
     * @throws WorkflowAnalysisException when the file cannot be read or parsed.
     */
    public Need analyze(Path workflowPath) {
        try {
            WorkflowYamlDto workflow = mapper.readValue(workflowPath.toFile(), WorkflowYamlDto.class);
            return analyze(workflow);
        } catch (IOException ex) {
            throw new WorkflowAnalysisException("unable to read workflow " + workflowPath + ": " + ex.getMessage(), ex);
        }
    }

    /**
     * Analyze in-memory workflow YAML supplied by an API client.
     *
     * @param workflowYaml YAML text for a Flow workflow document.
     * @param sourceName   human-readable source name used in diagnostics.
     * @return a suggested {@link Need} containing distinct node {@code uses} capabilities.
     * @throws WorkflowAnalysisException when the YAML cannot be parsed.
     */
    public Need analyzeContent(String workflowYaml, String sourceName) {
        try {
            WorkflowYamlDto workflow = mapper.readValue(workflowYaml, WorkflowYamlDto.class);
            return analyze(workflow);
        } catch (IOException ex) {
            String source = sourceName == null || sourceName.isBlank() ? "workflow content" : sourceName;
            throw new WorkflowAnalysisException("unable to read workflow " + source + ": " + ex.getMessage(), ex);
        }
    }

    /**
     * Project a parsed workflow DTO into a deterministic, de-duplicated capability need.
     *
     * @param workflow parsed workflow DTO.
     * @return a suggested {@link Need} with required capabilities sorted by capability name.
     * @throws WorkflowAnalysisException when the workflow object is absent.
     */
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

    /** Jackson DTO for the subset of Flow workflow YAML needed for capability extraction. */
    static final class WorkflowYamlDto {
        public String id;
        public String version;
        public List<WorkflowNodeDto> nodes;
    }

    /** Jackson DTO for a workflow node; {@code uses} is the DCP capability requirement source. */
    static final class WorkflowNodeDto {
        public String id;
        public String uses;
    }
}
