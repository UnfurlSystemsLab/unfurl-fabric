package com.unfurl.fabric.compile;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jdk8.Jdk8Module;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.unfurl.dcp.claim.ApiDocument;
import com.unfurl.dcp.claim.ArtifactRef;
import com.unfurl.dcp.claim.Claim;
import com.unfurl.dcp.claim.Offer;
import com.unfurl.dcp.claim.OperationDocumentation;
import com.unfurl.dcp.documentation.DocumentationArtifactStore.ArtifactUnavailableException;
import com.unfurl.dcp.documentation.PackagedArtifactStore;
import com.unfurl.dcp.contract.CompositionContract;
import com.unfurl.dcp.contract.ContractFreezer;
import com.unfurl.dcp.trust.ContractSigner;
import com.unfurl.dcp.trust.SigningKeyRef;
import com.unfurl.fabric.catalog.CatalogEntry;
import com.unfurl.fabric.compiler.CompiledContract;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Builder of the DCP runtime bundle (HLD-in-process-component-hosting §7): the broker-consumable artifacts a runtime
 * needs, namely every selected provider's claim ({@code claims/*.claim.json}, public DCP snake_case JSON) and one
 * individually signed frozen contract per child contract ({@code contracts/frozen/*.frozen.json}, through DCP's
 * {@link ContractFreezer}), plus the aggregate root frozen and signed the same way ({@value #ROOT_ENTRY}), whose
 * containment metadata pins the children so a host can verify the set with DCP's {@code VerifiedComposition} and decide
 * optional components from it. The aggregate Fabric contract stays the environment proof; these are the runtime broker's
 * inputs. Studio and {@code fabric sign --dcp-runtime-bundle} both write it here, so the two never differ.
 *
 * <p>It also carries every file those claims name through {@code artifact_ref} (offer payload schemas and API documents),
 * content-addressed at {@value #ARTIFACT_DIRECTORY}{@code <sha256>} (HLD-api-documentation-artifacts §3), which DCP's
 * {@code ContentAddressedArtifactStore} reads. Bytes come from each provider's own jar through DCP's
 * {@link PackagedArtifactStore}, which re-hashes them; claims are never rewritten, so claim hashes are unchanged.
 */
public final class DcpRuntimeBundleWriter {
    /** The bundle entry of the frozen, signed aggregate root, beside {@code contracts/frozen/}. */
    public static final String ROOT_ENTRY = "contracts/root.frozen.json";
    /** The bundle directory of documentation artifacts, one file per SHA-256 (DCP HLD-C2 A.12). */
    public static final String ARTIFACT_DIRECTORY = "artifacts/sha256/";
    /** A named file that cannot be read from its provider's jar, or does not match its hash. */
    public static final String BUNDLE_ARTIFACT_UNAVAILABLE = "BUNDLE_ARTIFACT_UNAVAILABLE";
    private final ObjectMapper claims = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .registerModule(new Jdk8Module())
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            .setSerializationInclusion(JsonInclude.Include.NON_NULL)
            .setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE);

    /**
     * Writes the bundle.
     *
     * @param compiled       the compiled contract whose child contracts are frozen
     * @param selected       the selected catalog entries, whose claims and documentation artifacts are included
     * @param signer         the signer that signs the aggregate contract, so one key verifies both
     * @param keyFingerprint the signer's public key fingerprint, recorded as the frozen contracts' key id
     * @return the zip bytes
     * @throws IllegalStateException {@value #BUNDLE_ARTIFACT_UNAVAILABLE}, naming the provider and path; nothing is
     *                               returned
     */
    public byte[] write(CompiledContract compiled, List<CatalogEntry> selected, ContractSigner signer, String keyFingerprint) {
        List<CatalogEntry> entries = selected == null ? List.of() : selected;
        Map<String, byte[]> artifacts = artifacts(entries);
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            try (ZipOutputStream zip = new ZipOutputStream(out, StandardCharsets.UTF_8)) {
                for (CatalogEntry entry : entries) {
                    Claim claim = entry.claimDescriptor().claim();
                    entry(zip, "claims/" + slug(claim.identity().uri().toString()) + ".claim.json", claims.writeValueAsBytes(claim));
                }
                for (Map.Entry<String, byte[]> artifact : artifacts.entrySet()) {
                    entry(zip, ARTIFACT_DIRECTORY + artifact.getKey(), artifact.getValue());
                }
                ContractFreezer freezer = new ContractFreezer(new SigningKeyRef(keyFingerprint));
                entry(zip, ROOT_ENTRY, freezer.freeze(compiled.contract(), signer).canonicalBytes());
                for (CompositionContract child : compiled.childContracts()) {
                    String capability = child.binding().providerCapability();
                    String id = child.contractId().toString();
                    String name = "contracts/frozen/" + slug(capability) + "-" + slug(id.substring(id.lastIndexOf(':') + 1)) + ".frozen.json";
                    entry(zip, name, freezer.freeze(child, signer).canonicalBytes());
                }
            }
            return out.toByteArray();
        } catch (IOException ex) {
            throw new IllegalStateException("unable to write DCP runtime bundle", ex);
        }
    }

    /**
     * Collects every file the entries' claims name, verified, keyed by SHA-256 in sorted order so the bundle is
     * deterministic. Every file is verified against its own hash, so one hash always names one file: a hash named twice,
     * for example a schema two offers share, is written once.
     */
    private static Map<String, byte[]> artifacts(List<CatalogEntry> entries) {
        Map<String, byte[]> byHash = new TreeMap<>();
        for (CatalogEntry entry : entries) {
            Claim claim = entry.claimDescriptor().claim();
            List<ArtifactRef> refs = references(claim);
            if (refs.isEmpty()) continue;
            String provider = claim.identity().uri() + "@" + claim.identity().version();
            if (entry.localPath() == null) {
                throw new IllegalStateException(BUNDLE_ARTIFACT_UNAVAILABLE + ": the jar of " + provider
                        + " is not available, so its documentation artifacts cannot be bundled");
            }
            PackagedArtifactStore store;
            try {
                store = PackagedArtifactStore.fromArchive(entry.localPath());
            } catch (ArtifactUnavailableException unreadable) {
                throw new IllegalStateException(BUNDLE_ARTIFACT_UNAVAILABLE + ": " + provider + ": " + unreadable.getMessage(), unreadable);
            }
            for (ArtifactRef ref : refs) {
                byte[] bytes;
                try {
                    bytes = store.read(ref);
                } catch (ArtifactUnavailableException unreadable) {
                    throw new IllegalStateException(BUNDLE_ARTIFACT_UNAVAILABLE + ": " + provider + " " + ref.path() + ": "
                            + unreadable.getMessage(), unreadable);
                }
                byHash.putIfAbsent(ref.sha256(), bytes);
            }
        }
        return byHash;
    }

    /** Every {@code artifact_ref} a claim names: each documented operation's two schemas, then each API document. */
    private static List<ArtifactRef> references(Claim claim) {
        List<ArtifactRef> refs = new ArrayList<>();
        for (Offer offer : claim.offers()) {
            if (offer.documentation() == null) continue;
            for (OperationDocumentation operation : offer.documentation().operations()) {
                refs.add(operation.requestSchema());
                refs.add(operation.responseSchema());
            }
        }
        for (ApiDocument document : claim.apiDocuments()) {
            refs.add(document.openapi());
        }
        return refs;
    }

    private static void entry(ZipOutputStream zip, String name, byte[] bytes) throws IOException {
        zip.putNextEntry(new ZipEntry(name));
        zip.write(bytes == null ? new byte[0] : bytes);
        zip.closeEntry();
    }

    /** File-name slug, identical to Studio's previous rule so existing bundles keep their entry names. */
    private static String slug(String value) {
        String slug = value == null ? "" : value.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-").replaceAll("^-|-$", "");
        return slug.isBlank() ? "local" : slug;
    }
}
