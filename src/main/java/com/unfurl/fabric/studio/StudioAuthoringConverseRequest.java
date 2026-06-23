package com.unfurl.fabric.studio;

import java.util.List;

public record StudioAuthoringConverseRequest(
        String tenantId,
        String assemblyId,
        String sessionId,
        List<StudioAuthoringConversationMessage> conversation,
        String userMessage
) {
    public StudioAuthoringConverseRequest {
        tenantId = tenantId == null || tenantId.isBlank() ? "tenant-local" : tenantId;
        assemblyId = assemblyId == null || assemblyId.isBlank() ? "assembly-demo" : assemblyId;
        sessionId = sessionId == null ? "" : sessionId;
        conversation = conversation == null ? List.of() : List.copyOf(conversation);
        userMessage = userMessage == null ? "" : userMessage;
    }
}
