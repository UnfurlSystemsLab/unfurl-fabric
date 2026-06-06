package com.unfurl.fabric.studio;

import java.time.Instant;

public record StudioCollaborator(
        String collaboratorId,
        String displayName,
        String selectedSurface,
        Instant lastSeenAt
) {
    public StudioCollaborator {
        collaboratorId = collaboratorId == null || collaboratorId.isBlank() ? "anonymous" : collaboratorId;
        displayName = displayName == null || displayName.isBlank() ? collaboratorId : displayName;
        selectedSurface = selectedSurface == null ? "" : selectedSurface;
        lastSeenAt = lastSeenAt == null ? Instant.EPOCH : lastSeenAt;
    }
}
