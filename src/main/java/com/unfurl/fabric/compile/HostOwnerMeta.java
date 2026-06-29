package com.unfurl.fabric.compile;

import java.net.URI;

/**
 * Immutable value object (DTO) describing the host/consumer identity that a compiled
 * {@link com.unfurl.dcp.contract.CompositionContract} is authored on behalf of.
 *
 * <p>Pattern: <b>Parameter Object</b> — it bundles the consumer-side metadata that
 * {@link ContractCompiler#compile} needs (the consumer party's claim URI/version and the Fabric
 * version stamped into provenance) so the compile signature stays small and additive. The compact
 * constructor applies safe defaults so callers may pass {@code null} for any field.
 *
 * @param consumerClaimUri     URI of the host/consumer claim that consumes the bound capability;
 *                             defaults to {@code urn:unfurl:host} when null.
 * @param consumerClaimVersion pinned version of the consumer claim; defaults to {@code 0.0.0} when blank.
 * @param fabricVersion        version of Fabric recorded in contract provenance; defaults to
 *                             {@code 0.1.0-SNAPSHOT} when blank.
 */
public record HostOwnerMeta(
        URI consumerClaimUri,
        String consumerClaimVersion,
        String fabricVersion
) {
    /**
     * Canonicalizing compact constructor: substitutes deterministic defaults for any null/blank
     * field so a compiled contract always has a well-formed consumer party and provenance stamp.
     */
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
