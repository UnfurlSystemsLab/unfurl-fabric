package com.unfurl.fabric.catalog;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

/**
 * Builds in-memory fixture JARs for catalog scanner tests. Each fixture JAR contains
 * {@code META-INF/unfurl-catalog.yaml} with a configurable claim block + catalog block.
 * No class files are written; this matches fabric's metadata-only treatment of catalog JARs.
 */
final class CatalogFixtures {

    private CatalogFixtures() {
    }

    static Path writeJar(Path dir, String fileName, String manifestYaml) throws IOException {
        Path target = dir.resolve(fileName);
        try (OutputStream out = Files.newOutputStream(target);
             JarOutputStream jar = new JarOutputStream(out)) {
            JarEntry entry = new JarEntry(CatalogScanner.MANIFEST_PATH);
            jar.putNextEntry(entry);
            jar.write(manifestYaml.getBytes(StandardCharsets.UTF_8));
            jar.closeEntry();
        }
        return target;
    }

    static Path writeJarRaw(Path dir, String fileName, String rawContent) throws IOException {
        Path target = dir.resolve(fileName);
        Files.writeString(target, rawContent, StandardCharsets.UTF_8);
        return target;
    }

    static Path writeEmptyJar(Path dir, String fileName) throws IOException {
        Path target = dir.resolve(fileName);
        try (OutputStream out = Files.newOutputStream(target);
             JarOutputStream jar = new JarOutputStream(out)) {
            // empty JAR — no entries
            jar.flush();
        }
        return target;
    }

    static byte[] jarBytes(String manifestYaml) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (JarOutputStream jar = new JarOutputStream(bytes)) {
            JarEntry entry = new JarEntry(CatalogScanner.MANIFEST_PATH);
            jar.putNextEntry(entry);
            jar.write(manifestYaml.getBytes(StandardCharsets.UTF_8));
            jar.closeEntry();
        }
        return bytes.toByteArray();
    }

    /**
     * Canonical ACTIVE-lifecycle storage-provider claim used by most happy-path tests.
     */
    static String storageS3Manifest() {
        return manifest(
                "urn:unfurl:storage:s3",
                "unfurl-storage-s3",
                "COMPONENT",
                "1.2.0",
                "Unfurl",
                "S3-compatible object storage provider",
                "ACTIVE",
                "com.unfurl:unfurl-storage-s3:1.2.0",
                "IN_PROCESS",
                "storage.put",
                "1.0.0");
    }

    static String storagePostgresManifest() {
        return manifest(
                "urn:unfurl:storage:postgres",
                "unfurl-storage-postgres",
                "COMPONENT",
                "1.0.0",
                "Unfurl",
                "Postgres-backed durable storage",
                "ACTIVE",
                "com.unfurl:unfurl-storage-postgres:1.0.0",
                "IN_PROCESS",
                "storage.put",
                "1.0.0");
    }

    static String identityIdpManifest() {
        return manifest(
                "urn:unfurl:identity:idp",
                "unfurl-identity-idp",
                "INTELLIGENT_COMPONENT",
                "2.1.0",
                "Unfurl",
                "Customer identity provider integration",
                "ACTIVE",
                "com.unfurl:unfurl-identity-idp:2.1.0",
                "IN_PROCESS",
                "identity.validate",
                "1.0.0");
    }

    static String experimentalManifest() {
        return manifest(
                "urn:unfurl:rag:vector-experimental",
                "unfurl-rag-vector-experimental",
                "INTELLIGENT_COMPONENT",
                "0.1.0",
                "Unfurl",
                "Experimental vector RAG provider",
                "EXPERIMENTAL",
                "com.unfurl:unfurl-rag-vector-experimental:0.1.0",
                "CONTAINER",
                "rag.search",
                "0.1.0");
    }

    static String blockedManifest() {
        return manifest(
                "urn:unfurl:vendor:legacy",
                "unfurl-vendor-legacy",
                "COMPONENT",
                "0.9.0",
                "Unfurl",
                "Legacy component blocked from new compositions",
                "BLOCKED",
                "com.unfurl:unfurl-vendor-legacy:0.9.0",
                "IN_PROCESS",
                "storage.put",
                "0.9.0");
    }

    static String missingClaimBlockManifest() {
        return """
                catalog:
                  lifecycle:
                    status: ACTIVE
                  artifact:
                    coordinates: com.unfurl:nothing:0.0.0
                    packaging: jar
                    source: catalog
                  binding:
                    default_mode: IN_PROCESS
                    supported_modes: [IN_PROCESS]
                """;
    }

    static String missingCatalogBlockManifest() {
        return """
                claim:
                  identity:
                    uri: urn:unfurl:noop
                    name: noop
                    kind: COMPONENT
                    version: 1.0.0
                    publisher: Unfurl
                  domain:
                    summary: no
                    concerns:
                      - concern: noop
                        description: nothing
                    boundary_principles:
                      - "nothing crosses the boundary"
                  refusals:
                    - concern: identity
                      rationale: nope
                      owned_by: someone-else
                  offers: []
                """;
    }

    static String manifest(
            String identityUri,
            String identityName,
            String identityKind,
            String identityVersion,
            String publisher,
            String domainSummary,
            String lifecycleStatus,
            String coordinates,
            String defaultBindingMode,
            String capabilityName,
            String capabilityVersion) {
        return String.format("""
                claim:
                  identity:
                    uri: %s
                    name: %s
                    kind: %s
                    version: %s
                    publisher: %s
                  domain:
                    summary: %s
                    concerns:
                      - concern: primary
                        description: primary concern of this component
                    boundary_principles:
                      - "deterministic boundary"
                  refusals:
                    - concern: identity-management
                      rationale: not our concern
                      owned_by: customer-idp
                  dependencies:
                    needs: []
                  offers:
                    - capability: %s
                      description: example offer
                      consumer_access: NAMED_COMPONENTS_ONLY
                      stability: STABLE
                      version: %s
                      metered: false
                catalog:
                  lifecycle:
                    status: %s
                  artifact:
                    coordinates: %s
                    packaging: jar
                    source: catalog
                  binding:
                    default_mode: %s
                    supported_modes: [%s]
                """,
                identityUri, identityName, identityKind, identityVersion, publisher,
                domainSummary,
                capabilityName, capabilityVersion,
                lifecycleStatus,
                coordinates,
                defaultBindingMode, defaultBindingMode);
    }
}
