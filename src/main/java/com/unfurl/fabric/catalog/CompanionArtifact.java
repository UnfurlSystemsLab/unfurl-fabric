package com.unfurl.fabric.catalog;

import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

/**
 * One companion jar in the catalog folder (HLD-in-process-component-hosting §3): a jar without a catalog manifest,
 * indexed by the Maven coordinates of its single {@code META-INF/maven/*}{@code /*}{@code /pom.properties}. It is not a
 * selectable entry; a hosted component's {@code hosted_activation.companions} names it, and compile pins its SHA-256.
 *
 * @param coordinates {@code group:artifact:version}
 * @param sha256      lower-case hex SHA-256 of the jar bytes
 * @param path        the jar the index keeps
 * @param copies      every path holding the same bytes, including {@code path}, sorted
 */
public record CompanionArtifact(String coordinates, String sha256, Path path, List<Path> copies) {
    /** Validating constructor. */
    public CompanionArtifact {
        Objects.requireNonNull(coordinates, "coordinates");
        Objects.requireNonNull(sha256, "sha256");
        Objects.requireNonNull(path, "path");
        copies = copies == null ? List.of(path) : List.copyOf(copies);
    }
}
