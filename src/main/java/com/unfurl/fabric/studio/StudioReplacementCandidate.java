package com.unfurl.fabric.studio;

public record StudioReplacementCandidate(
        String catalogEntryId,
        String label,
        String owner,
        String shape,
        String status,
        String reason
) {
    public StudioReplacementCandidate {
        catalogEntryId = catalogEntryId == null ? "" : catalogEntryId;
        label = label == null ? "" : label;
        owner = owner == null ? "" : owner;
        shape = shape == null ? "" : shape;
        status = status == null || status.isBlank() ? "BLOCKED" : status;
        reason = reason == null ? "" : reason;
    }
}
