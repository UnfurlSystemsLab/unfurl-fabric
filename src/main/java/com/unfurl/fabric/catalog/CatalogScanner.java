package com.unfurl.fabric.catalog;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

/**
 * Walks a directory of pre-built JARs and produces a deterministic {@link Catalog} plus a
 * diagnostic {@link CatalogScanReport}. Reads each JAR's {@code META-INF/unfurl-catalog.yaml}
 * via {@link CatalogManifestCodec} and computes the artifact SHA-256 + claim hash.
 *
 * <p><b>Fabric never executes catalog JAR code.</b> This scanner uses {@link JarFile} for
 * metadata access only; it never calls {@link Class#forName}, never constructs a
 * {@code URLClassLoader}, and never invokes {@link ClassLoader#loadClass(String)} on a catalog
 * class. Catalog JARs are treated as <i>packaged capability declarations</i>; their executable
 * code is loaded only by the runtime (flow), and only after the signed contract has been
 * verified.
 */
public final class CatalogScanner {

    static final String MANIFEST_PATH = "META-INF/unfurl-catalog.yaml";

    private final CatalogManifestCodec codec;
    private final Clock clock;

    public CatalogScanner() {
        this(new CatalogManifestCodec(), Clock.systemUTC());
    }

    public CatalogScanner(CatalogManifestCodec codec, Clock clock) {
        this.codec = codec == null ? new CatalogManifestCodec() : codec;
        this.clock = clock == null ? Clock.systemUTC() : clock;
    }

    public CatalogScanReport scan(Path catalogDirectory) {
        if (catalogDirectory == null) {
            throw new CatalogScanException("catalog directory path is null");
        }
        if (!Files.isDirectory(catalogDirectory)) {
            throw new CatalogScanException(
                    "catalog path is not a directory: " + catalogDirectory.toAbsolutePath());
        }

        List<CatalogEntry> entries = new ArrayList<>();
        List<SkippedEntry> skipped = new ArrayList<>();

        try (DirectoryStream<Path> stream = Files.newDirectoryStream(catalogDirectory, "*.jar")) {
            for (Path jarPath : stream) {
                try {
                    CatalogEntry entry = scanSingleJar(jarPath);
                    if (entry != null) {
                        entries.add(entry);
                    }
                } catch (SkipJar skip) {
                    skipped.add(new SkippedEntry(jarPath, skip.reason, skip.detail));
                }
            }
        } catch (IOException ex) {
            throw new CatalogScanException(
                    "unable to list catalog directory " + catalogDirectory + ": " + ex.getMessage(), ex);
        }

        Catalog catalog = new Catalog(entries);
        return new CatalogScanReport(catalog, Instant.now(clock), catalogDirectory, skipped);
    }

    private CatalogEntry scanSingleJar(Path jarPath) {
        byte[] jarBytes = readJarBytes(jarPath);
        String jarSha = sha256Hex(jarBytes);

        byte[] manifestBytes = readManifestBytes(jarPath);
        ParsedManifest parsed;
        try {
            parsed = codec.parse(manifestBytes);
        } catch (CatalogScanException ex) {
            throw new SkipJar(SkippedEntry.SkipReason.MALFORMED_MANIFEST, ex.getMessage());
        }

        AuthoredArtifact authored = parsed.authoredArtifact();
        ArtifactDescriptor artifact = new ArtifactDescriptor(
                authored.coordinates(),
                authored.packaging(),
                authored.source(),
                jarSha,
                authored.signature());

        ClaimDescriptor claimDescriptor = new ClaimDescriptor(
                parsed.claim(),
                codec.computeClaimHash(parsed.claim()));

        CatalogMetadata metadata = new CatalogMetadata(parsed.lifecycle(), parsed.binding());

        return new CatalogEntry(artifact, claimDescriptor, metadata, jarPath);
    }

    private byte[] readJarBytes(Path jarPath) {
        try {
            return Files.readAllBytes(jarPath);
        } catch (IOException ex) {
            throw new SkipJar(SkippedEntry.SkipReason.UNREADABLE_JAR,
                    "unable to read JAR bytes: " + ex.getMessage());
        }
    }

    private byte[] readManifestBytes(Path jarPath) {
        try (JarFile jar = new JarFile(jarPath.toFile(), false)) {
            JarEntry entry = jar.getJarEntry(MANIFEST_PATH);
            if (entry == null) {
                throw new SkipJar(SkippedEntry.SkipReason.MISSING_MANIFEST,
                        "JAR has no " + MANIFEST_PATH);
            }
            try (InputStream in = jar.getInputStream(entry)) {
                return in.readAllBytes();
            }
        } catch (IOException ex) {
            throw new SkipJar(SkippedEntry.SkipReason.UNREADABLE_JAR,
                    "unable to open JAR or read manifest: " + ex.getMessage());
        }
    }

    private static String sha256Hex(byte[] data) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(data);
            StringBuilder hex = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 not available on this JVM", ex);
        }
    }

    private static final class SkipJar extends RuntimeException {
        private final SkippedEntry.SkipReason reason;
        private final String detail;

        SkipJar(SkippedEntry.SkipReason reason, String detail) {
            super(detail);
            this.reason = reason;
            this.detail = detail;
        }
    }
}
