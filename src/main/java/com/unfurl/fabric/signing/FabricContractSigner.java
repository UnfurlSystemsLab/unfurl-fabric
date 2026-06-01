package com.unfurl.fabric.signing;

import com.unfurl.dcp.trust.ContractSigner;
import com.unfurl.dcp.trust.SignedContract;
import com.unfurl.dcp.trust.SigningKeyRef;
import com.unfurl.fabric.compiler.CompiledContract;
import com.unfurl.fabric.compiler.CompiledContractCodec;

import java.security.InvalidKeyException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.SignatureException;

/**
 * Fabric's signer. Implements {@link ContractSigner} (DCP's interface) so the DCP signing
 * primitive can be used directly, but the natural fabric-facing entry point is
 * {@link #signCompiledContract(CompiledContract)} which returns a {@link SignedFabricContract}
 * carrying the canonical hash + signature + key fingerprint.
 */
public final class FabricContractSigner implements ContractSigner {

    private final PrivateKey privateKey;
    private final String algorithm;
    private final String keyFingerprint;
    private final CompiledContractCodec codec;

    public FabricContractSigner(PrivateKey privateKey, String algorithm, String keyFingerprint) {
        this(privateKey, algorithm, keyFingerprint, new CompiledContractCodec());
    }

    public FabricContractSigner(PrivateKey privateKey, String algorithm, String keyFingerprint,
                                CompiledContractCodec codec) {
        if (privateKey == null) {
            throw new IllegalArgumentException("privateKey is required");
        }
        if (algorithm == null || algorithm.isBlank()) {
            throw new IllegalArgumentException("algorithm is required");
        }
        if (keyFingerprint == null || keyFingerprint.isBlank()) {
            throw new IllegalArgumentException("keyFingerprint is required");
        }
        this.privateKey = privateKey;
        this.algorithm = algorithm;
        this.keyFingerprint = keyFingerprint;
        this.codec = codec == null ? new CompiledContractCodec() : codec;
    }

    /**
     * Signs the given {@link CompiledContract}. The canonical bytes are derived via the
     * canonical codec; the SHA-256 of those bytes is stored on the resulting record alongside
     * the signature, so verification can recompute and compare without trusting any raw byte
     * blob authored by an operator.
     */
    public SignedFabricContract signCompiledContract(CompiledContract contract) {
        if (contract == null) {
            throw new IllegalArgumentException("contract is required");
        }
        byte[] canonicalBytes = codec.canonicalBytes(contract);
        String canonicalHash = sha256Hex(canonicalBytes);
        byte[] signatureBytes = signBytes(canonicalBytes);
        return new SignedFabricContract(contract, canonicalHash, signatureBytes,
                keyFingerprint, algorithm);
    }

    @Override
    public SignedContract sign(byte[] canonicalContractBytes, SigningKeyRef keyRef) {
        if (canonicalContractBytes == null || canonicalContractBytes.length == 0) {
            throw new IllegalArgumentException("canonical bytes are required");
        }
        byte[] signatureBytes = signBytes(canonicalContractBytes);
        String keyId = keyRef == null ? keyFingerprint : keyRef.keyId();
        return new SignedContract(canonicalContractBytes, signatureBytes, algorithm, keyId);
    }

    private byte[] signBytes(byte[] canonicalBytes) {
        try {
            Signature signer = Signature.getInstance(algorithm);
            signer.initSign(privateKey);
            signer.update(canonicalBytes);
            return signer.sign();
        } catch (NoSuchAlgorithmException ex) {
            throw new FabricSigningException("signature algorithm " + algorithm + " not available", ex);
        } catch (InvalidKeyException ex) {
            throw new FabricSigningException("private key incompatible with algorithm " + algorithm, ex);
        } catch (SignatureException ex) {
            throw new FabricSigningException("unable to sign canonical bytes: " + ex.getMessage(), ex);
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

    public String keyFingerprint() {
        return keyFingerprint;
    }

    public String algorithm() {
        return algorithm;
    }
}
