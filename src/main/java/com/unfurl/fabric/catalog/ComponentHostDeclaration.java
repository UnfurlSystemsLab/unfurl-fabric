package com.unfurl.fabric.catalog;

import java.util.List;

/**
 * Catalog metadata {@code catalog.component_host} (HLD-in-process-component-hosting §3): this component hosts
 * in-process components through DCP's in-process component host. Fabric uses it to place hosted components and to
 * fail early; the host enforces it at runtime.
 *
 * @param registrarShape    the registrar shape it admits components with
 * @param sharedApiPrefixes package prefixes it shares beyond DCP's base set, each ending in a dot; may be empty
 */
public record ComponentHostDeclaration(String registrarShape, List<String> sharedApiPrefixes) {
    /** Validating constructor: the shape is required and each prefix is dotted. */
    public ComponentHostDeclaration {
        if (registrarShape == null || registrarShape.isBlank()) {
            throw new IllegalArgumentException("component_host.registrar_shape is required");
        }
        sharedApiPrefixes = sharedApiPrefixes == null ? List.of() : List.copyOf(sharedApiPrefixes);
        if (sharedApiPrefixes.stream().anyMatch(prefix -> prefix == null || !prefix.endsWith("."))) {
            throw new IllegalArgumentException("component_host.shared_api_prefixes must each end with a dot");
        }
    }

    /** Returns whether a binary class or resource path lies under one of this host's own shared prefixes. */
    public boolean shares(String binaryName) {
        return sharedApiPrefixes.stream().anyMatch(binaryName::startsWith);
    }
}
