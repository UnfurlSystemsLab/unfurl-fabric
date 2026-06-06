package com.unfurl.fabric.studio;

import java.time.Instant;
import java.util.Map;

public record StudioIntentRecord(
        long revision,
        String collaboratorId,
        String type,
        Map<String, Object> payload,
        Instant acceptedAt
) {
    public StudioIntentRecord {
        collaboratorId = collaboratorId == null || collaboratorId.isBlank() ? "anonymous" : collaboratorId;
        type = type == null ? "" : type;
        payload = payload == null ? Map.of() : Map.copyOf(payload);
        acceptedAt = acceptedAt == null ? Instant.EPOCH : acceptedAt;
    }
}
