package com.unfurl.fabric.signing;

import com.unfurl.fabric.compile.ContractCompiler;
import com.unfurl.fabric.compile.HostOwnerMeta;
import com.unfurl.fabric.compiler.CompiledContract;
import com.unfurl.fabric.matcher.CandidateScore;
import com.unfurl.fabric.matcher.CompositionCandidate;
import com.unfurl.fabric.matcher.PlanningWarning;
import com.unfurl.fabric.needs.CapabilityRequirement;
import com.unfurl.fabric.needs.Need;
import com.unfurl.fabric.testing.FabricTestFixtures;
import com.unfurl.fabric.catalog.CatalogEntry;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.security.spec.ECGenParameterSpec;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.List;
import java.util.Set;

/**
 * Shared fixtures for signing + verify tests. Generates ephemeral EC keys (P-256, the
 * shortest curve that meets the SigningKeyLoader 256-bit floor) so tests never depend on
 * checked-in test keys.
 */
public final class SigningTestFixtures {

    public static final Clock FIXED_CLOCK = Clock.fixed(Instant.parse("2026-06-01T00:00:00Z"), ZoneOffset.UTC);

    public static KeyPair generateEcKeyPair() {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
            generator.initialize(new ECGenParameterSpec("secp256r1"));
            return generator.generateKeyPair();
        } catch (NoSuchAlgorithmException | java.security.InvalidAlgorithmParameterException ex) {
            throw new IllegalStateException("EC P-256 not available on this JVM", ex);
        }
    }

    public static Path writePublicKeyPem(Path dir, String fileName, java.security.PublicKey publicKey) throws IOException {
        Path target = dir.resolve(fileName);
        String base64 = Base64.getMimeEncoder(64, "\n".getBytes(StandardCharsets.UTF_8))
                .encodeToString(publicKey.getEncoded());
        String pem = "-----BEGIN PUBLIC KEY-----\n" + base64 + "\n-----END PUBLIC KEY-----\n";
        Files.writeString(target, pem);
        return target;
    }

    public static Path writePrivateKeyPem(Path dir, String fileName, java.security.PrivateKey privateKey) throws IOException {
        Path target = dir.resolve(fileName);
        String base64 = Base64.getMimeEncoder(64, "\n".getBytes(StandardCharsets.UTF_8))
                .encodeToString(privateKey.getEncoded());
        String pem = "-----BEGIN PRIVATE KEY-----\n" + base64 + "\n-----END PRIVATE KEY-----\n";
        Files.writeString(target, pem);
        return target;
    }

    public static CompiledContract sampleCompiledContract() {
        CatalogEntry entry = FabricTestFixtures.entry("storage-s3", "storage.put");
        List<CatalogEntry> entries = List.of(entry);
        CompositionCandidate candidate = new CompositionCandidate(
                CompositionCandidate.computeId(entries),
                entries,
                Set.of("storage.put"),
                Set.of(),
                List.of(),
                List.of(new PlanningWarning.OptionalCapabilityMissing("audit.write")),
                CandidateScore.of(0, 5, 10, 5, 30, 30, -3, 0));
        Need need = Need.ofRequiredCapabilities(CapabilityRequirement.requiredOf("storage.put", "^1"));
        HostOwnerMeta host = new HostOwnerMeta(URI.create("urn:operator:test"), "0.1.0", "0.1.0-SNAPSHOT");
        return new ContractCompiler(FIXED_CLOCK).compile(candidate, need, host);
    }

    private SigningTestFixtures() {
    }
}
