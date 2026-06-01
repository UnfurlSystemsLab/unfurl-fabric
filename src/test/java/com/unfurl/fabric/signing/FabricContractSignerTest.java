package com.unfurl.fabric.signing;

import com.unfurl.fabric.compiler.CompiledContract;
import org.junit.jupiter.api.Test;

import java.security.KeyPair;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FabricContractSignerTest {

    @Test
    void signsCompiledContractAndCarriesHashFingerprintAndAlgorithm() {
        KeyPair pair = SigningTestFixtures.generateEcKeyPair();
        String fingerprint = SigningKeyLoader.fingerprintOf(pair.getPublic());
        FabricContractSigner signer = new FabricContractSigner(
                pair.getPrivate(), "SHA256withECDSA", fingerprint);

        CompiledContract contract = SigningTestFixtures.sampleCompiledContract();
        SignedFabricContract signed = signer.signCompiledContract(contract);

        assertThat(signed.contract()).isEqualTo(contract);
        assertThat(signed.canonicalHash()).matches("[0-9a-f]{64}");
        assertThat(signed.signature()).isNotEmpty();
        assertThat(signed.signerKeyFingerprint()).isEqualTo(fingerprint);
        assertThat(signed.algorithm()).isEqualTo("SHA256withECDSA");
    }

    @Test
    void signingTheSameContractTwiceProducesTheSameCanonicalHash() {
        KeyPair pair = SigningTestFixtures.generateEcKeyPair();
        String fingerprint = SigningKeyLoader.fingerprintOf(pair.getPublic());
        FabricContractSigner signer = new FabricContractSigner(
                pair.getPrivate(), "SHA256withECDSA", fingerprint);

        CompiledContract contract = SigningTestFixtures.sampleCompiledContract();
        SignedFabricContract first = signer.signCompiledContract(contract);
        SignedFabricContract second = signer.signCompiledContract(contract);

        // Hash anchors the canonical bytes; bytes are recomputed from contract identically.
        assertThat(second.canonicalHash()).isEqualTo(first.canonicalHash());
        // Signatures themselves may differ (ECDSA is non-deterministic), but both verify
        // against the same canonical bytes; we only assert hash determinism here.
    }

    @Test
    void rejectsNullContract() {
        KeyPair pair = SigningTestFixtures.generateEcKeyPair();
        String fingerprint = SigningKeyLoader.fingerprintOf(pair.getPublic());
        FabricContractSigner signer = new FabricContractSigner(
                pair.getPrivate(), "SHA256withECDSA", fingerprint);

        assertThatThrownBy(() -> signer.signCompiledContract(null))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
