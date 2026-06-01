package com.unfurl.fabric.verify;

import com.unfurl.dcp.trust.VerificationKeySet;
import com.unfurl.fabric.signing.FabricSigningException;
import com.unfurl.fabric.signing.SigningKeyLoader;
import com.unfurl.fabric.signing.SigningTestFixtures;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TrustKeyDirectoryLoaderTest {
    private final TrustKeyDirectoryLoader loader = new TrustKeyDirectoryLoader();

    @Test
    void loadsPemPublicKeysFromDirectory(@TempDir Path dir) throws Exception {
        KeyPair pair = SigningTestFixtures.generateEcKeyPair();
        String fingerprint = SigningKeyLoader.fingerprintOf(pair.getPublic());
        SigningTestFixtures.writePublicKeyPem(dir, "fabric.pem", pair.getPublic());

        VerificationKeySet keys = loader.load(dir);

        assertThat(keys.find(fingerprint)).isPresent();
        assertThat(loader.fingerprintsIn(dir)).containsExactly(fingerprint);
    }

    @Test
    void rejectsNonDirectoryTrustKeyPath(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("not-a-directory.pem");
        Files.writeString(file, "not a directory");

        assertThatThrownBy(() -> loader.load(file))
                .isInstanceOf(FabricSigningException.class)
                .hasMessageContaining("not a directory");
    }
}
