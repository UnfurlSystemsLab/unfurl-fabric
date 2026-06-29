package com.unfurl.fabric.catalog;

/**
 * Runtime-relevant catalog metadata pairing an entry's {@link Lifecycle} with its
 * {@link BindingDescriptor}. (The authored artifact block is enriched separately into an
 * {@code ArtifactDescriptor} by the scanner.)
 *
 * <p>Pattern: immutable <b>value object</b>.
 *
 * @param lifecycle the lifecycle block (required).
 * @param binding   the binding descriptor (required).
 */
public record CatalogMetadata(
        Lifecycle lifecycle,
        BindingDescriptor binding
) {
    /** Compact constructor: requires both lifecycle and binding. */
    public CatalogMetadata {
        if (lifecycle == null) {
            throw new IllegalArgumentException("lifecycle is required");
        }
        if (binding == null) {
            throw new IllegalArgumentException("binding is required");
        }
    }
}
