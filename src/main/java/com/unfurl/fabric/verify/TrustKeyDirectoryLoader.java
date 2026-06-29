package com.unfurl.fabric.verify;

import com.unfurl.dcp.trust.VerificationKey;
import com.unfurl.dcp.trust.VerificationKeySet;
import com.unfurl.fabric.signing.FabricSigningException;
import com.unfurl.fabric.signing.SigningKeyLoader;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Loads a directory of {@code *.pem} public-key files into a {@link VerificationKeySet}.
 * Each key file's fingerprint (SHA-256 of the SubjectPublicKeyInfo) is used as its key ID,
 * so signed contracts referencing the fingerprint resolve directly to the loaded key.
 *
 * <p>Pattern: <b>Loader/Builder</b> — reads filesystem inputs and assembles a domain object
 * ({@code VerificationKeySet}); all I/O failures are wrapped as {@link FabricSigningException}.
 */
public final class TrustKeyDirectoryLoader {

    /**
     * Load every {@code *.pem} in the directory into a fingerprint-keyed verification key set.
     *
     * <p>Files are processed in deterministic filename order. Each key's fingerprint is both its map
     * key and the {@code VerificationKey} id, so verification can resolve a signer by fingerprint.
     *
     * @param directory the directory containing PEM public keys (required, must exist).
     * @return the assembled verification key set.
     * @throws FabricSigningException if the path is null, not a directory, or cannot be listed.
     */
    public VerificationKeySet load(Path directory) {
        if (directory == null) {
            throw new FabricSigningException("trust-keys directory is required");
        }
        if (!Files.isDirectory(directory)) {
            throw new FabricSigningException("trust-keys path is not a directory: "
                    + directory.toAbsolutePath());
        }
        List<Path> pemFiles = new ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(directory, "*.pem")) {
            for (Path p : stream) {
                pemFiles.add(p);
            }
        } catch (IOException ex) {
            throw new FabricSigningException("unable to list trust-keys directory "
                    + directory + ": " + ex.getMessage(), ex);
        }
        pemFiles.sort(Comparator.comparing(Path::getFileName));

        Map<String, VerificationKey> keys = new HashMap<>();
        for (Path pem : pemFiles) {
            SigningKeyLoader.LoadedPublicKey loaded = SigningKeyLoader.loadPublicKey(pem);
            keys.put(loaded.fingerprint(), new VerificationKey(loaded.fingerprint(), loaded.key()));
        }
        return new VerificationKeySet(keys);
    }

    /**
     * Convenience listing of the key fingerprints (ids) that would be loaded from the directory.
     *
     * @param directory the trust-keys directory.
     * @return the list of loaded key fingerprints/ids.
     * @throws FabricSigningException on the same conditions as {@link #load(Path)}.
     */
    public List<String> fingerprintsIn(Path directory) {
        VerificationKeySet set = load(directory);
        return new ArrayList<>(set.keysById().keySet());
    }
}
