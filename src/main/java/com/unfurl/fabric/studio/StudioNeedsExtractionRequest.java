package com.unfurl.fabric.studio;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Data Transfer Object: carries operator source hints for DCP needs extraction.
 *
 * <p>Pattern: immutable API DTO. `fileNames` preserves the original lightweight Studio request,
 * while `files` allows richer clients to send inline source content for deterministic analysis.
 * The compact constructor normalizes nulls, trims names, and merges inline file names into the
 * backward-compatible `fileNames` view.
 *
 * @param targetApplicationName  operator-facing application label.
 * @param fileNames              source file names used for metadata-only inference.
 * @param defaultDeploymentTarget deployment target hint copied into the response.
 * @param files                  optional inline source files for content-aware extraction.
 */
public record StudioNeedsExtractionRequest(
        String targetApplicationName,
        List<String> fileNames,
        String defaultDeploymentTarget,
        List<StudioNeedsExtractionSourceFile> files
) {
    /**
     * Backward-compatible constructor for existing clients that only send source names.
     *
     * @param targetApplicationName  operator-facing application label.
     * @param fileNames              source file names used for metadata-only inference.
     * @param defaultDeploymentTarget deployment target hint copied into the response.
     */
    public StudioNeedsExtractionRequest(
            String targetApplicationName,
            List<String> fileNames,
            String defaultDeploymentTarget
    ) {
        this(targetApplicationName, fileNames, defaultDeploymentTarget, List.of());
    }

    /**
     * Compact constructor: normalizes all fields and keeps `fileNames` as the union of explicit
     * names and inline source file names.
     */
    public StudioNeedsExtractionRequest {
        targetApplicationName = targetApplicationName == null || targetApplicationName.isBlank()
                ? "target-application"
                : targetApplicationName.trim();
        defaultDeploymentTarget = defaultDeploymentTarget == null ? "" : defaultDeploymentTarget.trim();
        files = files == null ? List.of() : List.copyOf(files);
        Set<String> names = new LinkedHashSet<>();
        if (fileNames != null) {
            fileNames.stream()
                    .filter(name -> name != null && !name.isBlank())
                    .map(String::trim)
                    .forEach(names::add);
        }
        files.stream()
                .map(StudioNeedsExtractionSourceFile::fileName)
                .filter(name -> !name.isBlank())
                .forEach(names::add);
        fileNames = List.copyOf(names);
    }
}
