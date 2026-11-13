package com.unfurl.fabric.compile;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jdk8.Jdk8Module;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.unfurl.dcp.claim.Claim;
import com.unfurl.dcp.contract.CompositionContract;
import com.unfurl.dcp.contract.ContractFreezer;
import com.unfurl.dcp.trust.ContractSigner;
import com.unfurl.dcp.trust.SigningKeyRef;
import com.unfurl.fabric.catalog.CatalogEntry;
import com.unfurl.fabric.compiler.CompiledContract;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
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
 */
public final class DcpRuntimeBundleWriter {
    /** The bundle entry of the frozen, signed aggregate root, beside {@code contracts/frozen/}. */
    public static final String ROOT_ENTRY = "contracts/root.frozen.json";
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
     * @param selected       the selected catalog entries, whose claims are included
     * @param signer         the signer that signs the aggregate contract, so one key verifies both
     * @param keyFingerprint the signer's public key fingerprint, recorded as the frozen contracts' key id
     * @return the zip bytes
     */
    public byte[] write(CompiledContract compiled, List<CatalogEntry> selected, ContractSigner signer, String keyFingerprint) {
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            try (ZipOutputStream zip = new ZipOutputStream(out, StandardCharsets.UTF_8)) {
                for (CatalogEntry entry : selected == null ? List.<CatalogEntry>of() : selected) {
                    Claim claim = entry.claimDescriptor().claim();
                    entry(zip, "claims/" + slug(claim.identity().uri().toString()) + ".claim.json", claims.writeValueAsBytes(claim));
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
