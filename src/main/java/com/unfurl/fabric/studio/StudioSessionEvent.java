package com.unfurl.fabric.studio;

import java.time.Instant;

public record StudioSessionEvent(
        String eventId,
        String type,
        StudioDraftSession session,
        Instant emittedAt
) {
    public StudioSessionEvent {
        eventId = eventId == null ? "" : eventId;
        type = type == null || type.isBlank() ? "session" : type;
        emittedAt = emittedAt == null ? Instant.EPOCH : emittedAt;
    }
}
