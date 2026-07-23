package com.unfurl.fabric.studio;

import java.util.List;
import java.util.Map;

/**
 * Data Transfer Object: command body for one Studio authoring-agent turn.
 *
 * <p>Pattern: command DTO. Optional catalog metadata lets the Foundry-backed
 * agent bind its proposal to the same immutable tenant catalog file version
 * that the workspace and draft session use; blank values mean Fabric should
 * select the latest tenant catalog version.
 */
public record StudioAuthoringConverseRequest(
        String tenantId,
        String assemblyId,
        String sessionId,
        String catalogFileId,
        String displayName,
        List<StudioAuthoringConversationMessage> conversation,
        String userMessage,
        Map<String, Object> questionAnswers,
        Map<String, Object> actionContext
) {
    /**
     * Convenience constructor: preserves callers that supplied action-scoped
     * visual edit context before structured clarification answers existed.
     */
    public StudioAuthoringConverseRequest(
            String tenantId,
            String assemblyId,
            String sessionId,
            String catalogFileId,
            String displayName,
            List<StudioAuthoringConversationMessage> conversation,
            String userMessage,
            Map<String, Object> actionContext
    ) {
        this(tenantId, assemblyId, sessionId, catalogFileId, displayName, conversation, userMessage, Map.of(), actionContext);
    }

    /**
     * Convenience constructor: preserves callers that supplied catalog-file
     * metadata before action-scoped visual edit assist existed.
     */
    public StudioAuthoringConverseRequest(
            String tenantId,
            String assemblyId,
            String sessionId,
            String catalogFileId,
            String displayName,
            List<StudioAuthoringConversationMessage> conversation,
            String userMessage
    ) {
        this(tenantId, assemblyId, sessionId, catalogFileId, displayName, conversation, userMessage, Map.of(), Map.of());
    }

    /**
     * Convenience constructor: preserves older callers that only supplied the
     * conversation identity and prompt before catalog-file history existed.
     */
    public StudioAuthoringConverseRequest(
            String tenantId,
            String assemblyId,
            String sessionId,
            List<StudioAuthoringConversationMessage> conversation,
            String userMessage
    ) {
        this(tenantId, assemblyId, sessionId, "", "", conversation, userMessage, Map.of(), Map.of());
    }

    /**
     * Data Transfer Object invariant: normalizes nullable fields while keeping
     * catalog selection optional so Fabric can resolve the tenant latest row.
     */
    public StudioAuthoringConverseRequest {
        tenantId = tenantId == null || tenantId.isBlank() ? "tenant-local" : tenantId;
        assemblyId = assemblyId == null || assemblyId.isBlank() ? "assembly-demo" : assemblyId;
        sessionId = sessionId == null ? "" : sessionId;
        catalogFileId = catalogFileId == null ? "" : catalogFileId;
        displayName = displayName == null ? "" : displayName;
        conversation = conversation == null ? List.of() : List.copyOf(conversation);
        userMessage = userMessage == null ? "" : userMessage;
        questionAnswers = questionAnswers == null ? Map.of() : Map.copyOf(questionAnswers);
        actionContext = actionContext == null ? Map.of() : Map.copyOf(actionContext);
    }
}
