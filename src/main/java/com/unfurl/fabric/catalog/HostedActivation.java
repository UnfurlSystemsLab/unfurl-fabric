package com.unfurl.fabric.catalog;

import java.util.List;

/**
 * Catalog metadata {@code catalog.hosted_activation} (HLD-in-process-component-hosting §3): the component runs inside a
 * host that admits it through DCP's in-process component host. It is catalog metadata, not claim content, so it
 * changes the artifact hash but never the claim hash.
 *
 * @param registrarShapes the registrar shapes the component's DCP {@code ComponentActivation} supports
 * @param companions      Maven coordinates ({@code group:artifact:version}) of every other artifact the component
 *                        needs at runtime, beyond what its host shares (DCP decision D1); may be empty
 */
public record HostedActivation(List<String> registrarShapes, List<String> companions) {
    /** Validating constructor: at least one registrar shape; coordinates are non-blank and unique. */
    public HostedActivation {
        registrarShapes = registrarShapes == null ? List.of() : List.copyOf(registrarShapes);
        companions = companions == null ? List.of() : List.copyOf(companions);
        if (registrarShapes.isEmpty() || registrarShapes.stream().anyMatch(shape -> shape == null || shape.isBlank())) {
            throw new IllegalArgumentException("hosted_activation.registrar_shapes needs at least one non-blank shape");
        }
        if (companions.stream().anyMatch(c -> c == null || c.split(":").length < 3)) {
            throw new IllegalArgumentException("hosted_activation.companions are group:artifact:version coordinates");
        }
        if (companions.stream().distinct().count() != companions.size()) {
            throw new IllegalArgumentException("hosted_activation.companions lists a coordinate twice");
        }
    }
}
