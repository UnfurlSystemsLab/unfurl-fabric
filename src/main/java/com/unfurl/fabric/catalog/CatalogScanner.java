package com.unfurl.fabric.catalog;

import com.unfurl.fabric.artifact.ArtifactDescriptor;

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
 *
 * <p>Pattern: <b>service</b> with injected collaborators (codec + clock); skips are signalled via an
 * internal control-flow exception ({@link SkipJar}) so one bad JAR never aborts the whole scan.
 */
public final class CatalogScanner {

    /** Path within each JAR where the catalog manifest is expected. */
    static final String MANIFEST_PATH = "META-INF/unfurl-catalog.yaml";

    /** Manifest parser / claim-hash computer. */
    private final CatalogManifestCodec codec;
    /** Time source for the scan timestamp (injectable for tests). */
    private final Clock clock;

    /** Production constructor: default codec and system UTC clock. */
    public CatalogScanner() {
        this(new CatalogManifestCodec(), Clock.systemUTC());
    }

    /**
     * Test/seam constructor injecting collaborators.
     *
     * @param codec the manifest codec (null → default).
     * @param clock the clock (null → system UTC).
     */
    public CatalogScanner(CatalogManifestCodec codec, Clock clock) {
        this.codec = codec == null ? new CatalogManifestCodec() : codec;
        this.clock = clock == null ? Clock.systemUTC() : clock;
    }

    /**
     * Scan a directory of {@code *.jar} files into a catalog + diagnostic report. Each JAR is parsed for
     * its catalog manifest; unreadable/malformed/missing-manifest JARs are recorded as skips rather than
     * failing the scan. Only a missing/inaccessible directory is fatal.
     *
     * @param catalogDirectory the directory to scan (required, must be a directory).
     * @return the scan report (catalog snapshot + scan time/source + skipped entries).
     * @throws CatalogScanException if the path is null, not a directory, or cannot be listed.
     */
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

    /**
     * Scan one JAR into a {@link CatalogEntry}: compute its SHA-256, parse its manifest, enrich the
     * authored artifact with the computed SHA, and compute the claim hash.
     *
     * @param jarPath the JAR to scan.
     * @return the catalog entry.
     * @throws SkipJar if the JAR is unreadable, lacks a manifest, or the manifest is malformed.
     */
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

        return new CatalogEntry(artifact, claimDescriptor, metadata, parsed.componentShapeProfile(), jarPath);
    }

    /**
     * Read the full JAR bytes (for SHA-256 computation).
     *
     * @param jarPath the JAR path.
     * @return the JAR bytes.
     * @throws SkipJar if the JAR cannot be read.
     */
    private byte[] readJarBytes(Path jarPath) {
        try {
            return Files.readAllBytes(jarPath);
        } catch (IOException ex) {
            throw new SkipJar(SkippedEntry.SkipReason.UNREADABLE_JAR,
                    "unable to read JAR bytes: " + ex.getMessage());
        }
    }

    /**
     * Read the catalog manifest entry's bytes from the JAR (metadata access only; no code execution).
     *
     * @param jarPath the JAR path.
     * @return the manifest bytes.
     * @throws SkipJar if the manifest is missing or the JAR cannot be opened/read.
     */
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

    /**
     * Compute a lowercase hex SHA-256 of the given bytes.
     *
     * @param data the input bytes.
     * @return the 64-char lowercase hex digest.
     * @throws IllegalStateException if SHA-256 is unavailable on the JVM.
     */
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

    /**
     * Internal control-flow signal carrying a skip reason + detail, caught per-JAR in {@link #scan} so a
     * single bad JAR is recorded as a {@link SkippedEntry} rather than aborting the whole scan.
     */
    private static final class SkipJar extends RuntimeException {
        /** The skip category. */
        private final SkippedEntry.SkipReason reason;
        /** The human-readable detail. */
        private final String detail;

        /**
         * @param reason the skip category.
         * @param detail the human-readable detail.
         */
        SkipJar(SkippedEntry.SkipReason reason, String detail) {
            super(detail);
            this.reason = reason;
            this.detail = detail;
        }
    }
}
