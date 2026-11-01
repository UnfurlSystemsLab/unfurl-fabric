package com.unfurl.fabric.assembly;

import com.fasterxml.jackson.databind.JsonNode;
import com.unfurl.dcp.claim.Claim;
import com.unfurl.dcp.claim.ClaimValidator;
import com.unfurl.fabric.catalog.CatalogManifestCodec;
import com.unfurl.fabric.needs.Need;
import com.unfurl.fabric.needs.NeedsCodec;
import java.io.IOException;
import java.util.Set;

/** Validator/Projector: interprets existing catalog manifests and needs as reusable publisher source, without new DCP fields. */
public record AssemblySource(Claim claim, Need need) {
    /** Parse source bytes, rejecting unsupported profile semantics before the lenient existing needs codec can ignore them. */
    public static AssemblySource parse(byte[] definition, byte[] needs) throws IOException {
        JsonNode manifest = AssemblyCodec.YAML.readTree(definition);
        require(manifest != null && manifest.isObject() && manifest.has("claim"), "catalog claim envelope required");
        JsonNode dependencies = manifest.path("claim").get("dependencies");
        if (dependencies != null) fields(dependencies, Set.of("needs"));
        Claim claim = new CatalogManifestCodec().parse(definition).claim();
        require(new ClaimValidator().validate(claim).valid(), "invalid composite claim");
        require(claim.identity() != null && claim.identity().uri() != null && claim.identity().uri().isAbsolute(), "absolute logical URI required");
        require(claim.identity().name() != null && !claim.identity().name().isBlank()
                && claim.identity().publisher() != null && !claim.identity().publisher().isBlank()
                && claim.identity().kind() != null, "complete claim identity required");
        version(claim.identity().version());
        require(claim.metadata() != null && claim.identity().version().equals(claim.metadata().claimVersion()), "claim version metadata mismatch");
        JsonNode tree = AssemblyCodec.YAML.readTree(needs);
        fields(tree, Set.of("requiredCapabilities", "optionalCapabilities", "artifactConstraints", "refusalExpectations", "bindingPreferences", "trustPolicyRef"));
        require(!tree.hasNonNull("trustPolicyRef"), "profile trustPolicyRef requires a pinned policy lifecycle; use admitted signed compiler inputs");
        for (String key : new String[]{"requiredCapabilities", "optionalCapabilities"}) {
            JsonNode items = tree.get(key);
            if (items != null) {
                require(items.isArray(), key + " must be an array");
                for (JsonNode item : items) fields(item, Set.of("capability", "capabilityVersion", "requiredOfferDetails", "required_offer_details"));
            }
        }
        if (tree.has("artifactConstraints")) {
            require(tree.get("artifactConstraints").isArray(), "artifactConstraints must be an array");
            for (JsonNode item : tree.get("artifactConstraints")) fields(item, Set.of("group", "name", "version"));
        }
        return new AssemblySource(claim, new NeedsCodec().parse(needs));
    }

    /** Strict input boundary for supported object fields; arbitrary offer-detail maps keep their existing DCP semantics. */
    private static void fields(JsonNode node, Set<String> allowed) throws IOException {
        require(node != null && node.isObject(), "source profile must contain objects");
        var names = node.fieldNames();
        while (names.hasNext()) {
            String name = names.next();
            require(allowed.contains(name), "unsupported profile field: " + name);
        }
    }

    /** Portable locator guard used for source names, profiles, tenants, environments and immutable revision names. */
    public static String name(String value) throws IOException {
        require(value != null && value.matches("[a-z0-9][a-z0-9_-]{0,63}")
                && !value.matches("(?i)(con|prn|aux|nul|com[1-9]|lpt[1-9])"), "unsafe locator name: " + value);
        return value;
    }

    /** Release version guard: strict SemVer, independent of public capability and claim versions. */
    public static String version(String value) throws IOException {
        require(value != null && value.matches("(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)(-[0-9A-Za-z-]+(\\.[0-9A-Za-z-]+)*)?(\\+[0-9A-Za-z-]+(\\.[0-9A-Za-z-]+)*)?"), "invalid SemVer: " + value);
        if (value.contains("-")) {
            for (String identifier : value.split("-", 2)[1].split("\\+", 2)[0].split("\\."))
                require(!identifier.matches("0[0-9]+"), "numeric prerelease identifier has leading zero");
        }
        return value;
    }

    /** Family guard: preserve the existing three composition families rather than infer them from directory names. */
    public static String family(String value) throws IOException {
        require(Set.of("flow-only", "foundry-only", "flow-foundry").contains(value), "unknown assembly family: " + value);
        return value;
    }

    /** Validation helper: expected bad inputs fail without publishing any final bundle. */
    static void require(boolean condition, String message) throws IOException {
        if (!condition) throw new IOException(message);
    }
}
