package com.unfurl.fabric.studio;

import java.util.Objects;

/**
 * Value Object: one companion jar a tenant admitted to Studio (HLD-in-process-component-hosting §3). The bytes live in
 * {@link StudioCompanionStore} under {@code sha256}; this record is what Studio persists and resolves a hosted
 * component's {@code hosted_activation.companions} against at compile.
 *
 * @param coordinates the jar's one {@code group:artifact:version}, from its {@code pom.properties}
 * @param sha256      lowercase hex SHA-256 of the stored bytes (no {@code sha256:} prefix, as pinned in contracts)
 * @param fileName    the uploaded file name, named in conflict refusals
 */
public record StudioCompanionRecord(String coordinates, String sha256, String fileName) {
    /** Validating constructor: every field is required. */
    public StudioCompanionRecord {
        Objects.requireNonNull(coordinates, "coordinates");
        Objects.requireNonNull(sha256, "sha256");
        fileName = fileName == null ? "" : fileName;
    }
}
