package com.unfurl.fabric.verify;

/**
 * Result of verifying a {@code SignedFabricContract}'s signature + canonical-hash anchor.
 * Strictly about the cryptographic side: signature validity, key pinning, hash consistency.
 * Catalog drift is a separate concern with its own report.
 */
public sealed interface SignatureVerificationResult
        permits SignatureVerificationResult.Ok,
                SignatureVerificationResult.KeyNotPinned,
                SignatureVerificationResult.SignatureInvalid,
                SignatureVerificationResult.HashMismatch {

    boolean ok();

    record Ok(String fingerprint, String algorithm) implements SignatureVerificationResult {
        public boolean ok() { return true; }
    }

    record KeyNotPinned(String fingerprint, String detail) implements SignatureVerificationResult {
        public KeyNotPinned(String fingerprint) {
            this(fingerprint, "signing key fingerprint " + fingerprint + " is not in the trust set");
        }
        public boolean ok() { return false; }
    }

    record SignatureInvalid(String fingerprint, String detail) implements SignatureVerificationResult {
        public SignatureInvalid(String fingerprint) {
            this(fingerprint, "signature did not verify for key " + fingerprint);
        }
        public boolean ok() { return false; }
    }

    record HashMismatch(String expectedHash, String actualHash, String detail)
            implements SignatureVerificationResult {
        public HashMismatch(String expectedHash, String actualHash) {
            this(expectedHash, actualHash,
                    "canonical bytes recomputed from contract hash to " + actualHash
                            + " but signed envelope declares " + expectedHash);
        }
        public boolean ok() { return false; }
    }
}
