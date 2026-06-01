package com.unfurl.fabric.compiler;

import java.net.URI;

public record HostOwnerMeta(
        URI consumerClaimUri,
        String consumerClaimVersion,
        String fabricVersion
) {
    public HostOwnerMeta {
        if (consumerClaimUri == null) {
            consumerClaimUri = URI.create("urn:unfurl:host");
        }
        if (consumerClaimVersion == null || consumerClaimVersion.isBlank()) {
            consumerClaimVersion = "0.0.0";
        }
        if (fabricVersion == null || fabricVersion.isBlank()) {
            fabricVersion = "0.1.0-SNAPSHOT";
        }
    }
}
