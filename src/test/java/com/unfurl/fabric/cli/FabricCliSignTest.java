package com.unfurl.fabric.cli;

import com.unfurl.fabric.signing.SignedFabricContract;
import com.unfurl.fabric.signing.SignedFabricContractCodec;
import com.unfurl.fabric.signing.SigningTestFixtures;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;

import static org.assertj.core.api.Assertions.assertThat;

class FabricCliSignTest {
    @Test
    void signsCompiledContractAndWritesParseableEnvelope(@TempDir Path dir) throws Exception {
        Path catalog = dir.resolve("catalog");
        CliTestFixtures.writeCatalogJar(catalog, "storage.jar", "storage-s3", "storage.put");
        Path needs = CliTestFixtures.writeNeeds(dir, "storage.put");
        Path compiled = dir.resolve("compiled.yaml");
        assertThat(CliTestFixtures.run("compile", "--catalog", catalog.toString(),
                "--needs", needs.toString(), "--out", compiled.toString()).exitCode()).isEqualTo(0);

        KeyPair pair = SigningTestFixtures.generateEcKeyPair();
        Path privateKey = SigningTestFixtures.writePrivateKeyPem(dir, "key.pem", pair.getPrivate());
        Path publicKey = SigningTestFixtures.writePublicKeyPem(dir, "pub.pem", pair.getPublic());
        Path signedPath = dir.resolve("signed.yaml");

        CliTestFixtures.CliRun result = CliTestFixtures.run("sign", "--contract", compiled.toString(),
                "--key", privateKey.toString(), "--public-key", publicKey.toString(), "--out", signedPath.toString());

        assertThat(result.exitCode()).isEqualTo(0);
        SignedFabricContract signed = new SignedFabricContractCodec().parse(Files.readAllBytes(signedPath));
        assertThat(signed.canonicalHash()).matches("[0-9a-f]{64}");
        assertThat(signed.signature()).isNotEmpty();
        assertThat(signed.signerKeyFingerprint()).startsWith("sha256:");
    }
}
