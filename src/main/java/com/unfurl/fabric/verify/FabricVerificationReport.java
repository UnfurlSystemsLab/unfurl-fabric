package com.unfurl.fabric.verify;

import com.unfurl.fabric.signing.SignatureVerificationResult;

import java.util.Optional;

/**
 * Combined verification outcome for {@code fabric verify}. Carries the signature result
 * (always present) and the catalog drift report (present only when a catalog was supplied).
 *
 * <p>The two halves are explicitly independent: a contract may have a valid signature while
 * the catalog has drifted, or vice versa. The CLI reports both so the operator can decide
 * whether to re-sign, re-compile, or accept the differences.
 *
 * <p>Pattern: immutable <b>value object</b> with named static factories ({@link #signatureOnly},
 * {@link #of}) for the two construction modes.
 *
 * @param signature    the signature verification result (required).
 * @param catalogDrift the optional catalog drift report (empty when no catalog was supplied).
 */
public record FabricVerificationReport(
        SignatureVerificationResult signature,
        Optional<CatalogDriftReport> catalogDrift
) {
    /** Compact constructor: requires a signature result and normalizes a null optional to empty. */
    public FabricVerificationReport {
        if (signature == null) {
            throw new IllegalArgumentException("signature result is required");
        }
        if (catalogDrift == null) {
            catalogDrift = Optional.empty();
        }
    }

    /**
     * Aggregate pass/fail: signature must be OK and, if a drift report is present, it must be clean.
     *
     * @return true iff signature is valid and the catalog (if checked) has not drifted.
     */
    public boolean overallOk() {
        if (!signature.ok()) {
            return false;
        }
        return catalogDrift.map(CatalogDriftReport::clean).orElse(true);
    }

    /**
     * Factory for the signature-only case (no catalog supplied / drift not checked).
     *
     * @param signature the signature verification result.
     * @return a report with an empty catalog-drift section.
     */
    public static FabricVerificationReport signatureOnly(SignatureVerificationResult signature) {
        return new FabricVerificationReport(signature, Optional.empty());
    }

    /**
     * Factory combining a signature result with an optional drift report.
     *
     * @param signature the signature verification result.
     * @param drift     the catalog drift report, or null if none.
     * @return the combined report.
     */
    public static FabricVerificationReport of(SignatureVerificationResult signature, CatalogDriftReport drift) {
        return new FabricVerificationReport(signature, Optional.ofNullable(drift));
    }
}
