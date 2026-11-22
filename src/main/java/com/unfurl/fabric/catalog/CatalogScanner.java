package com.unfurl.fabric.catalog;

import com.unfurl.dcp.claim.Claim;
import com.unfurl.dcp.claim.ClaimValidator;
import com.unfurl.dcp.documentation.ClaimArtifactValidator;
import com.unfurl.dcp.documentation.PackagedArtifactStore;
import com.unfurl.dcp.validation.Diagnostic;
import com.unfurl.dcp.validation.ErrorCode;
import com.unfurl.dcp.validation.SchemaValidationReport;
import com.unfurl.dcp.validation.Severity;
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
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
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
 *
 * <p>Each claim's shipped documentation is verified as data (HLD-api-documentation-artifacts §2): DCP's claim rules for
 * {@code documentation} and {@code api_documents}, then DCP's {@link ClaimArtifactValidator} over the files they name,
 * read from the very jar bytes that were hashed. A failure is a {@link SkippedEntry.SkipReason#DOCUMENTATION_ARTIFACT_INVALID}
 * skip naming DCP's code and the path; DCP's documentation-hint warning is a {@link CatalogScanReport.ScanWarning}.
 *
 * <p>Jars without a catalog manifest are offered to the {@link CompanionIndex} (HLD-in-process-component-hosting §3).
 * A hosted entry whose companions are missing or conflicting is reported as
 * {@link SkippedEntry.SkipReason#COMPANION_UNRESOLVED} and left out of the catalog, so it is never selectable.
 */
public final class CatalogScanner {

    /** Path within each JAR where the catalog manifest is expected. */
    static final String MANIFEST_PATH = "META-INF/unfurl-catalog.yaml";

    /**
     * DCP claim-validation codes about a claim's documentation shape (DCP HLD-C2 A.6, A.12, A.13). Only these skip an
     * entry here: the scanner verifies documentation, and the claim's other rules are the planner's concern.
     */
    static final Set<ErrorCode> DOCUMENTATION_CODES = EnumSet.of(
            ErrorCode.CLAIM_DOCUMENTATION_INCOMPLETE,
            ErrorCode.CLAIM_DOCUMENTATION_OPERATION_DUPLICATE,
            ErrorCode.CLAIM_DOCUMENTATION_MEDIA_TYPE,
            ErrorCode.ARTIFACT_REF_INVALID,
            ErrorCode.CLAIM_API_DOCUMENT_INVALID,
            ErrorCode.CLAIM_EXTERNAL_PROTOCOL_INVALID);

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
        List<CatalogScanReport.ScanWarning> warnings = new ArrayList<>();
        CompanionIndex companions = new CompanionIndex();

        List<Path> jars = new ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(catalogDirectory, "*.jar")) {
            stream.forEach(jars::add);
        } catch (IOException ex) {
            throw new CatalogScanException(
                    "unable to list catalog directory " + catalogDirectory + ": " + ex.getMessage(), ex);
        }
        // Sorted, so the index and every diagnostic are independent of directory listing order.
        jars.sort(java.util.Comparator.comparing(Path::toString));
        for (Path jarPath : jars) {
            try {
                CatalogEntry entry = scanSingleJar(jarPath, warnings);
                if (entry != null) {
                    entries.add(entry);
                }
            } catch (SkipJar skip) {
                // A jar without a catalog manifest may be a companion (§3); it is a skip only when it is not one.
                if (skip.reason == SkippedEntry.SkipReason.MISSING_MANIFEST) {
                    Optional<String> notCompanion = companions.offer(jarPath);
                    if (notCompanion.isEmpty()) continue;
                    skipped.add(new SkippedEntry(jarPath, skip.reason, skip.detail + "; " + notCompanion.get()));
                } else {
                    skipped.add(new SkippedEntry(jarPath, skip.reason, skip.detail));
                }
            }
        }

        // An entry whose companions are missing or conflicting is not selectable, and says why.
        List<CatalogEntry> selectable = new ArrayList<>();
        for (CatalogEntry entry : entries) {
            Optional<String> unresolved = entry.metadata().optionalHostedActivation().flatMap(companions::unresolved);
            Optional<String> invalidConfiguration = invalidConfiguration(entry);
            if (invalidConfiguration.isPresent()) {
                skipped.add(new SkippedEntry(entry.localPath(), SkippedEntry.SkipReason.INVALID_COMPONENT_CONFIGURATION,
                        invalidConfiguration.get()));
            } else if (unresolved.isPresent()) {
                skipped.add(new SkippedEntry(entry.localPath(), SkippedEntry.SkipReason.COMPANION_UNRESOLVED, unresolved.get()));
            } else {
                selectable.add(entry.metadata().optionalHostedActivation().map(companions::resolve).map(entry::withCompanions).orElse(entry));
            }
        }

        Catalog catalog = new Catalog(selectable, companions.artifacts());
        return new CatalogScanReport(catalog, Instant.now(clock), catalogDirectory, skipped, warnings);
    }

    /**
     * Scan one JAR into a {@link CatalogEntry}: compute its SHA-256, parse its manifest, enrich the
     * authored artifact with the computed SHA, and compute the claim hash.
     *
     * @param jarPath  the JAR to scan.
     * @param warnings where warnings about a still-selectable entry are added.
     * @return the catalog entry.
     * @throws SkipJar if the JAR is unreadable, lacks a manifest, the manifest is malformed, or its shipped
     *                 documentation is invalid.
     */
    private CatalogEntry scanSingleJar(Path jarPath, List<CatalogScanReport.ScanWarning> warnings) {
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
        verifyDocumentation(parsed.claim(), jarBytes, jarPath, warnings);

        CatalogMetadata metadata = parsed.metadata();

        return new CatalogEntry(artifact, claimDescriptor, metadata, parsed.componentShapeProfile(), jarPath);
    }

    /**
     * Verifies a claim's shipped documentation as data (HLD-api-documentation-artifacts §2): first DCP's claim rules for
     * the documentation shape, then, when the claim names any file, DCP's artifact rules over those files read from
     * {@code jarBytes}, the bytes whose SHA-256 the artifact descriptor carries. Classes are never loaded.
     *
     * @throws SkipJar {@code DOCUMENTATION_ARTIFACT_INVALID}, listing every problem as {@code CODE field: message}
     */
    private static void verifyDocumentation(Claim claim, byte[] jarBytes, Path jarPath,
                                            List<CatalogScanReport.ScanWarning> warnings) {
        SchemaValidationReport shape = new ClaimValidator().validate(claim);
        List<Diagnostic> invalid = shape.diagnostics().stream()
                .filter(d -> d.severity() == Severity.ERROR && DOCUMENTATION_CODES.contains(d.code())).toList();
        if (!invalid.isEmpty()) {
            throw new SkipJar(SkippedEntry.SkipReason.DOCUMENTATION_ARTIFACT_INVALID, describe(invalid));
        }
        shape.diagnostics().stream()
                .filter(d -> d.severity() == Severity.WARNING && d.code() == ErrorCode.CLAIM_DOCUMENTATION_HINT_ONLY)
                .forEach(d -> warnings.add(new CatalogScanReport.ScanWarning(jarPath, d.code().name(),
                        d.fieldPath() + ": " + d.message())));
        boolean namesFiles = !claim.apiDocuments().isEmpty()
                || claim.offers().stream().anyMatch(offer -> offer.documentation() != null);
        if (!namesFiles) {
            return;
        }
        SchemaValidationReport artifacts = new ClaimArtifactValidator().validate(claim,
                PackagedArtifactStore.fromArchiveBytes(jarBytes, String.valueOf(jarPath.getFileName())));
        List<Diagnostic> failures = artifacts.diagnostics().stream().filter(d -> d.severity() == Severity.ERROR).toList();
        if (!failures.isEmpty()) {
            throw new SkipJar(SkippedEntry.SkipReason.DOCUMENTATION_ARTIFACT_INVALID, describe(failures));
        }
    }

    /** Diagnostic text of a documentation skip: each problem as {@code CODE field: message}, DCP's code first. */
    private static String describe(List<Diagnostic> diagnostics) {
        return diagnostics.stream().map(d -> d.code().name() + " " + d.fieldPath() + ": " + d.message())
                .collect(Collectors.joining("; "));
    }

    /**
     * Checks the entry's packaged {@code META-INF/unfurl-config.yaml}, as data, with DCP's schema parser.
     *
     * @return empty when the entry packages no configuration or a valid one; otherwise every failure
     */
    private static Optional<String> invalidConfiguration(CatalogEntry entry) {
        try {
            com.unfurl.fabric.runtime.HostedComponentConfiguration.packagedSchema(entry.localPath());
            return Optional.empty();
        } catch (com.unfurl.dcp.componentconfig.ComponentConfigurationException invalid) {
            return Optional.of(invalid.getMessage());
        } catch (IOException unreadable) {
            return Optional.of("the packaged configuration cannot be read: " + unreadable.getMessage());
        }
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
        try {
            return readCatalogManifest(jarPath).orElseThrow(() -> new SkipJar(SkippedEntry.SkipReason.MISSING_MANIFEST,
                    "JAR has no " + MANIFEST_PATH));
        } catch (IOException ex) {
            throw new SkipJar(SkippedEntry.SkipReason.UNREADABLE_JAR,
                    "unable to open JAR or read manifest: " + ex.getMessage());
        }
    }

    /**
     * Reads a JAR's catalog manifest bytes exactly as packaged (metadata access only; no code execution). Studio keeps
     * these bytes as the claim document for a scanned entry, so the claim it compiles is the one the JAR carries.
     *
     * @param jarPath the JAR path.
     * @return the manifest bytes, or empty when the JAR has no {@code META-INF/unfurl-catalog.yaml}.
     * @throws IOException if the JAR cannot be opened or the entry cannot be read.
     */
    public static Optional<byte[]> readCatalogManifest(Path jarPath) throws IOException {
        try (JarFile jar = new JarFile(jarPath.toFile(), false)) {
            JarEntry entry = jar.getJarEntry(MANIFEST_PATH);
            if (entry == null) {
                return Optional.empty();
            }
            try (InputStream in = jar.getInputStream(entry)) {
                return Optional.of(in.readAllBytes());
            }
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
