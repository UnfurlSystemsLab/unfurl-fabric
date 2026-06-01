package com.unfurl.fabric.signing;

import com.unfurl.fabric.compiler.CompiledContract;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.KeyPair;

import static org.assertj.core.api.Assertions.assertThat;

class SignedFabricContractCodecTest {
    @Test
    void roundTripsSignedContractYaml() {
        KeyPair pair = SigningTestFixtures.generateEcKeyPair();
        String fingerprint = SigningKeyLoader.fingerprintOf(pair.getPublic());
        FabricContractSigner signer = new FabricContractSigner(
                pair.getPrivate(), "SHA256withECDSA", fingerprint);
        CompiledContract contract = SigningTestFixtures.sampleCompiledContract();
        SignedFabricContract signed = signer.signCompiledContract(contract);
        SignedFabricContractCodec codec = new SignedFabricContractCodec();

        byte[] yaml = codec.write(signed);
        SignedFabricContract parsed = codec.parse(yaml);

        assertThat(new String(yaml, StandardCharsets.UTF_8))
                .contains("canonicalHash:")
                .contains("signature:")
                .contains("contract:");
        assertThat(parsed.contract()).isEqualTo(signed.contract());
        assertThat(parsed.canonicalHash()).isEqualTo(signed.canonicalHash());
        assertThat(parsed.signature()).isEqualTo(signed.signature());
        assertThat(parsed.signerKeyFingerprint()).isEqualTo(fingerprint);
        assertThat(parsed.algorithm()).isEqualTo("SHA256withECDSA");
    }
}
