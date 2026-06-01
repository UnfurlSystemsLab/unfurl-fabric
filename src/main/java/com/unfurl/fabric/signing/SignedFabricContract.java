package com.unfurl.fabric.signing;

import com.unfurl.fabric.compiler.CompiledContract;

/**
 * Fabric's signed contract envelope. Carries the deserialized {@link CompiledContract},
 * a SHA-256 hash of the canonical bytes that were signed, plus the signature itself and
 * the metadata needed to look up the verification key.
 *
 * <p>Raw canonical bytes are deliberately <b>not</b> stored on this record. The verifier
 * re-derives them from {@link #contract()} via the canonical codec on demand, then asserts
 * the hash and signature against the re-derived bytes. This keeps the record small,
 * keeps the contract content type-safe, and ensures the hash anchors integrity rather than
 * a byte blob the operator might tamper with by hand.
 */
public record SignedFabricContract(
        CompiledContract contract,
        String canonicalHash,
        byte[] signature,
        String signerKeyFingerprint,
        String algorithm
) {
    public SignedFabricContract {
        if (contract == null) {
            throw new IllegalArgumentException("contract is required");
        }
        if (canonicalHash == null || canonicalHash.isBlank()) {
            throw new IllegalArgumentException("canonicalHash is required");
        }
        if (signature == null || signature.length == 0) {
            throw new IllegalArgumentException("signature is required");
        }
        if (signerKeyFingerprint == null || signerKeyFingerprint.isBlank()) {
            throw new IllegalArgumentException("signerKeyFingerprint is required");
        }
        if (algorithm == null || algorithm.isBlank()) {
            throw new IllegalArgumentException("algorithm is required");
        }
        signature = signature.clone();
    }

    @Override
    public byte[] signature() {
        return signature.clone();
    }
}
