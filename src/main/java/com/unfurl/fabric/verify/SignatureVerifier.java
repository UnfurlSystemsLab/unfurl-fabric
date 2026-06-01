package com.unfurl.fabric.verify;

import com.unfurl.dcp.trust.VerificationKey;
import com.unfurl.dcp.trust.VerificationKeySet;
import com.unfurl.fabric.compiler.CompiledContractCodec;
import com.unfurl.fabric.signing.FabricSigningException;
import com.unfurl.fabric.signing.SignedFabricContract;

import java.security.InvalidKeyException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.Signature;
import java.security.SignatureException;
import java.util.Optional;

/**
 * Verifies that a {@link SignedFabricContract}'s signature matches the canonical bytes
 * re-derived from its embedded {@code contract}, using a pinned trust-key set.
 *
 * <p>This is the <i>cryptographic</i> verification path only. Catalog drift (whether the
 * SHA-256s on each {@code SelectionRecord} still match the JARs in the current catalog) is
 * a separate concern with its own checker. The two are intentionally split: a deployment
 * machine without the catalog present should still be able to run the signature check.
 */
public final class SignatureVerifier {

    private final CompiledContractCodec codec;

    public SignatureVerifier() {
        this(new CompiledContractCodec());
    }

    public SignatureVerifier(CompiledContractCodec codec) {
        this.codec = codec == null ? new CompiledContractCodec() : codec;
    }

    public SignatureVerificationResult verify(SignedFabricContract signed, VerificationKeySet keys) {
        if (signed == null) {
            throw new FabricSigningException("signed contract is required");
        }
        if (keys == null) {
            throw new FabricSigningException("verification key set is required");
        }

        byte[] canonicalBytes = codec.canonicalBytes(signed.contract());
        String recomputedHash = sha256Hex(canonicalBytes);
        if (!recomputedHash.equals(signed.canonicalHash())) {
            return new SignatureVerificationResult.HashMismatch(
                    signed.canonicalHash(), recomputedHash);
        }

        Optional<VerificationKey> key = keys.find(signed.signerKeyFingerprint());
        if (key.isEmpty()) {
            return new SignatureVerificationResult.KeyNotPinned(signed.signerKeyFingerprint());
        }

        try {
            Signature verifier = Signature.getInstance(signed.algorithm());
            verifier.initVerify(key.get().publicKey());
            verifier.update(canonicalBytes);
            if (!verifier.verify(signed.signature())) {
                return new SignatureVerificationResult.SignatureInvalid(signed.signerKeyFingerprint());
            }
            return new SignatureVerificationResult.Ok(
                    signed.signerKeyFingerprint(), signed.algorithm());
        } catch (NoSuchAlgorithmException ex) {
            throw new FabricSigningException("signature algorithm " + signed.algorithm()
                    + " not available on this JVM", ex);
        } catch (InvalidKeyException ex) {
            return new SignatureVerificationResult.SignatureInvalid(signed.signerKeyFingerprint());
        } catch (SignatureException ex) {
            return new SignatureVerificationResult.SignatureInvalid(signed.signerKeyFingerprint());
        }
    }

    private static String sha256Hex(byte[] data) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(data);
            StringBuilder sb = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException ex) {
            throw new FabricSigningException("SHA-256 not available on this JVM", ex);
        }
    }
}
