package com.unfurl.fabric.verify;

import com.unfurl.fabric.signing.SignatureVerificationResult;
import com.unfurl.fabric.signing.SignatureVerifier;
import com.unfurl.dcp.trust.VerificationKey;
import com.unfurl.dcp.trust.VerificationKeySet;
import com.unfurl.fabric.compiler.CompiledContract;
import com.unfurl.fabric.signing.FabricContractSigner;
import com.unfurl.fabric.signing.SignedFabricContract;
import com.unfurl.fabric.signing.SigningKeyLoader;
import com.unfurl.fabric.signing.SigningTestFixtures;
import org.junit.jupiter.api.Test;

import java.security.KeyPair;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SignatureVerifierTest {
    private final SignatureVerifier verifier = new SignatureVerifier();

    @Test
    void reportsKeyNotPinnedWhenFingerprintIsAbsent() {
        SignedFabricContract signed = signedWithFreshKey().signed();

        SignatureVerificationResult result = verifier.verify(signed, VerificationKeySet.of(List.of()));

        assertThat(result).isInstanceOf(SignatureVerificationResult.KeyNotPinned.class);
        assertThat(result.ok()).isFalse();
    }

    @Test
    void reportsSignatureInvalidWhenSignatureBytesAreTampered() {
        SignedWithKey fixture = signedWithFreshKey();
        byte[] signature = fixture.signed().signature();
        signature[0] = (byte) (signature[0] ^ 0x01);
        SignedFabricContract tampered = new SignedFabricContract(
                fixture.signed().contract(),
                fixture.signed().canonicalHash(),
                signature,
                fixture.signed().signerKeyFingerprint(),
                fixture.signed().algorithm());

        SignatureVerificationResult result = verifier.verify(tampered, fixture.keys());

        assertThat(result).isInstanceOf(SignatureVerificationResult.SignatureInvalid.class);
        assertThat(result.ok()).isFalse();
    }

    @Test
    void reportsHashMismatchWhenCanonicalHashIsTampered() {
        SignedWithKey fixture = signedWithFreshKey();
        SignedFabricContract tampered = new SignedFabricContract(
                fixture.signed().contract(),
                "0000000000000000000000000000000000000000000000000000000000000000",
                fixture.signed().signature(),
                fixture.signed().signerKeyFingerprint(),
                fixture.signed().algorithm());

        SignatureVerificationResult result = verifier.verify(tampered, fixture.keys());

        assertThat(result).isInstanceOf(SignatureVerificationResult.HashMismatch.class);
        assertThat(result.ok()).isFalse();
    }

    @Test
    void returnsOkWhenHashSignatureAndPinnedKeyMatch() {
        SignedWithKey fixture = signedWithFreshKey();

        SignatureVerificationResult result = verifier.verify(fixture.signed(), fixture.keys());

        assertThat(result).isInstanceOf(SignatureVerificationResult.Ok.class);
        assertThat(result.ok()).isTrue();
    }

    private static SignedWithKey signedWithFreshKey() {
        KeyPair pair = SigningTestFixtures.generateEcKeyPair();
        String fingerprint = SigningKeyLoader.fingerprintOf(pair.getPublic());
        CompiledContract contract = SigningTestFixtures.sampleCompiledContract();
        SignedFabricContract signed = new FabricContractSigner(
                pair.getPrivate(), "SHA256withECDSA", fingerprint)
                .signCompiledContract(contract);
        VerificationKeySet keys = VerificationKeySet.of(
                List.of(new VerificationKey(fingerprint, pair.getPublic())));
        return new SignedWithKey(signed, keys);
    }

    private record SignedWithKey(SignedFabricContract signed, VerificationKeySet keys) {
    }
}
