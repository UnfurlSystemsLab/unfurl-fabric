package com.unfurl.fabric.verify;

import java.util.Optional;

/**
 * Combined verification outcome for {@code fabric verify}. Carries the signature result
 * (always present) and the catalog drift report (present only when a catalog was supplied).
 *
 * <p>The two halves are explicitly independent: a contract may have a valid signature while
 * the catalog has drifted, or vice versa. The CLI reports both so the operator can decide
 * whether to re-sign, re-compile, or accept the differences.
 */
public record FabricVerificationReport(
        SignatureVerificationResult signature,
        Optional<CatalogDriftReport> catalogDrift
) {
    public FabricVerificationReport {
        if (signature == null) {
            throw new IllegalArgumentException("signature result is required");
        }
        if (catalogDrift == null) {
            catalogDrift = Optional.empty();
        }
    }

    public boolean overallOk() {
        if (!signature.ok()) {
            return false;
        }
        return catalogDrift.map(CatalogDriftReport::clean).orElse(true);
    }

    public static FabricVerificationReport signatureOnly(SignatureVerificationResult signature) {
        return new FabricVerificationReport(signature, Optional.empty());
    }

    public static FabricVerificationReport of(SignatureVerificationResult signature, CatalogDriftReport drift) {
        return new FabricVerificationReport(signature, Optional.ofNullable(drift));
    }
}
