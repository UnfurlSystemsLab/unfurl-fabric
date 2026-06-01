package com.unfurl.fabric.catalog;

public record CatalogMetadata(
        Lifecycle lifecycle,
        BindingDescriptor binding
) {
    public CatalogMetadata {
        if (lifecycle == null) {
            throw new IllegalArgumentException("lifecycle is required");
        }
        if (binding == null) {
            throw new IllegalArgumentException("binding is required");
        }
    }
}
