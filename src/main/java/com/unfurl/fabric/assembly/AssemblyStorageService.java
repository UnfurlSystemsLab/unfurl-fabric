package com.unfurl.fabric.assembly;

import com.fasterxml.jackson.databind.JsonNode;
import com.unfurl.dcp.contract.*;
import com.unfurl.dcp.runtimebinding.RuntimeBinding;
import com.unfurl.dcp.runtimebinding.RuntimeBindingValidator;
import com.unfurl.dcp.trust.*;
import com.unfurl.fabric.artifact.ArtifactDescriptor;
import com.unfurl.fabric.catalog.*;
import com.unfurl.fabric.compiler.CompiledContract;
import com.unfurl.fabric.matcher.CandidateValidator;
import com.unfurl.fabric.signing.*;
import com.unfurl.substrate.api.SubstrateProfileCodec;
import java.io.*;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.jar.JarInputStream;
import static com.unfurl.fabric.assembly.AssemblySource.*;

/** Application Service: validates and signs reusable release/binding indexes through DCP ports, with no workload execution. */
public final class AssemblyStorageService {
    private static final String INDEX = "manifest.json";
    private final ArtifactRepository releases;
    private final VerificationKeySet keys;

    /** Inject a repository port and host-pinned trust keys; no provider or transport is chosen by the service. */
    public AssemblyStorageService(ArtifactRepository releases, VerificationKeySet keys) {
        this.releases = Objects.requireNonNull(releases);
        this.keys = Objects.requireNonNull(keys);
    }

    /** Initialize a real publisher source from supplied manifests; immutable initialization cannot replace an existing source. */
    public void initialize(ArtifactRepository workspace, String family, String name, String profile,
                           byte[] definition, byte[] needs) throws IOException {
        AssemblySource.parse(definition, needs);
        workspace.put("assemblies/" + family(family) + "/" + name(name), Map.of(
                "definition.yaml", definition, "profiles/" + name(profile) + ".yaml", needs));
    }

    /** Publish exact signed compiler output and source/profile provenance; verifies all inputs before committing a final key. */
    public AssemblyIndex publish(Path source, String profile, String releaseVersion, byte[] signedBytes,
                                 byte[] substrateBytes, Path catalog, ContractSigner signer, SigningKeyRef key) throws IOException {
        name(profile); version(releaseVersion);
        String family = family(source.toAbsolutePath().normalize().getParent().getFileName().toString());
        Map<String, byte[]> files = new TreeMap<>();
        files.put("source/definition.yaml", readSource(source.resolve("definition.yaml")));
        files.put("source/profiles/" + profile + ".yaml", readSource(source.resolve("profiles/" + profile + ".yaml")));
        AssemblySource definition = source(files, profile);
        SignedFabricContract signed = signed(signedBytes);
        files.put("compiled/signed.yaml", signedBytes);
        files.put("compiled/substrate.yaml", substrateBytes);
        Bundle previous = null;
        byte[] existingIndex = null;
        try { existingIndex = releases.read(releaseKey(definition.claim().identity().uri().toString(), releaseVersion), INDEX); }
        catch (NoSuchFileException absent) { /* A new version has no committed index. */ }
        if (existingIndex != null) {
            previous = load(releases, releaseKey(definition.claim().identity().uri().toString(), releaseVersion));
            validateRelease(previous.index(), previous.files());
        }
        Map<URI, CompositionContract> contracts = contracts(signed.contract());
        for (CompositionContract contract : contracts.values()) {
            String path = contractPath(contract.contractId());
            files.put(path, previous == null ? new ContractFreezer(key).freeze(contract, signer).canonicalBytes() : required(previous.files(), path));
        }
        Catalog available = new CatalogScanner().scan(catalog).catalog();
        for (var selection : signed.contract().selections()) {
            CatalogEntry entry = available.entries().stream().filter(e -> e.artifact().coordinates().equals(selection.artifact().coordinates())
                    && e.artifact().sha256().equals(selection.artifact().sha256())).findFirst()
                    .orElseThrow(() -> new IOException("selected artifact missing or drifted: " + selection.artifact().coordinates()));
            files.put("artifacts/" + selection.artifact().sha256() + ".jar", readSource(entry.localPath()));
        }
        AssemblyIndex index = new AssemblyIndex(1, "release", definition.claim().identity().uri().toString(),
                definition.claim().identity().version(), releaseVersion, family, profile, null, null, null, null, hashes(files));
        validateRelease(index, files);
        commit(releases, releaseKey(index.uri(), releaseVersion), index, files, signer, key);
        return index;
    }

    /** Reload a release using only the repository and pinned keys; signature, hashes and semantic closure are checked again. */
    public AssemblyIndex verify(String uri, String version) throws IOException {
        Bundle bundle = load(releases, releaseKey(uri, version));
        require(bundle.index().uri().equals(uri) && bundle.index().releaseVersion().equals(version), "release locator mismatch");
        validateRelease(bundle.index(), bundle.files());
        return bundle.index();
    }

    /** Save one deployment-owner-signed revision of a binding set after release and DCP tree verification. */
    public AssemblyIndex bind(ArtifactRepository deployments, String uri, String version, String tenant,
                              String environment, String revision, byte[] bindings, ContractSigner signer, SigningKeyRef key) throws IOException {
        Bundle release = verifiedRelease(uri, version);
        Map<String, byte[]> files = new TreeMap<>(Map.of("runtime-bindings.yaml", bindings));
        AssemblyIndex index = new AssemblyIndex(1, "deployment", uri, release.index().claimVersion(), version,
                release.index().family(), release.index().profile(), name(tenant), name(environment), name(revision),
                hash(releases.read(releaseKey(uri, version), INDEX)), hashes(files));
        validateBindings(index, bindings, release);
        commit(deployments, deploymentKey(uri, version, tenant, environment, revision), index, files, signer, key);
        return index;
    }

    /** Verify a saved deployment revision against its exact release index and every frozen contract edge. */
    public AssemblyIndex verifyBinding(ArtifactRepository deployments, String uri, String version, String tenant,
                                       String environment, String revision) throws IOException {
        Bundle release = verifiedRelease(uri, version);
        Bundle bundle = load(deployments, deploymentKey(uri, version, tenant, environment, revision));
        AssemblyIndex index = bundle.index();
        require(index.kind().equals("deployment") && index.uri().equals(uri) && index.releaseVersion().equals(version)
                && Objects.equals(index.tenant(), tenant) && Objects.equals(index.environment(), environment)
                && Objects.equals(index.revision(), revision), "deployment locator mismatch");
        require(index.files().keySet().equals(Set.of("runtime-bindings.yaml")), "unexpected deployment inventory");
        require(Objects.equals(index.releaseManifestHash(), hash(releases.read(releaseKey(uri, version), INDEX))), "release manifest drift");
        validateBindings(index, bundle.files().get("runtime-bindings.yaml"), release);
        return index;
    }

    /** Repository locator: logical URI is hashed to avoid filesystem collisions; release SemVer remains independently readable. */
    public static String releaseKey(String uri, String releaseVersion) throws IOException {
        require(uri != null && URI.create(uri).isAbsolute(), "absolute release URI required");
        return "releases/" + hash(uri.getBytes(StandardCharsets.UTF_8)) + "/" + version(releaseVersion);
    }

    /** Deployment locator: tenant/environment scope and immutable revision are outside the release identity. */
    public static String deploymentKey(String uri, String version, String tenant, String environment, String revision) throws IOException {
        return "deployments/" + name(tenant) + "/" + name(environment) + "/" + releaseKey(uri, version).substring("releases/".length()) + "/" + name(revision);
    }

    /** Load and validate a release once for binding workflows. */
    private Bundle verifiedRelease(String uri, String version) throws IOException {
        Bundle bundle = load(releases, releaseKey(uri, version));
        require(bundle.index().uri().equals(uri) && bundle.index().releaseVersion().equals(version), "release locator mismatch");
        validateRelease(bundle.index(), bundle.files());
        return bundle;
    }

    /** Authenticate a strict canonical index before reading any of its payload paths, then enforce every byte hash. */
    private Bundle load(ArtifactRepository repository, String key) throws IOException {
        SignedContract signed = AssemblyCodec.JSON.readValue(repository.read(key, INDEX), SignedContract.class);
        require(new OfflineContractVerifier().verify(signed, keys).valid(), "manifest signature verification failed");
        AssemblyIndex index = AssemblyCodec.JSON.readValue(signed.canonicalBytes(), AssemblyIndex.class);
        require(index.formatVersion() == 1 && Arrays.equals(AssemblyCodec.bytes(index), signed.canonicalBytes()), "unsupported or noncanonical manifest");
        require(index.files().size() > 0 && index.files().size() <= 10000, "invalid manifest inventory size");
        Map<String, byte[]> files = new TreeMap<>();
        for (var entry : index.files().entrySet()) {
            byte[] bytes = repository.read(key, entry.getKey());
            require(hash(bytes).equals(entry.getValue()), "artifact hash mismatch: " + entry.getKey());
            files.put(entry.getKey(), bytes);
        }
        return new Bundle(index, files);
    }

    /** Commit a signed index; retries compare authenticated index bytes, allowing randomized signatures without replacing a release. */
    private void commit(ArtifactRepository repository, String target, AssemblyIndex index, Map<String, byte[]> files,
                        ContractSigner signer, SigningKeyRef key) throws IOException {
        SignedContract signature = signer.sign(AssemblyCodec.bytes(index), key);
        require(new OfflineContractVerifier().verify(signature, keys).valid(), "publication signer is not pinned or key pair mismatches");
        files.put(INDEX, AssemblyCodec.bytes(signature));
        // Existing immutable data is never overwritten. A cryptographically equivalent retry uses its retained signature bytes.
        byte[] existing = null;
        try { existing = repository.read(target, INDEX); } catch (NoSuchFileException ex) { /* New key. */ }
        catch (FileNotFoundException ex) { /* New key. */ }
        if (existing != null) {
            Bundle old = load(repository, target);
            require(Arrays.equals(AssemblyCodec.bytes(old.index()), AssemblyCodec.bytes(index)), "immutable index conflict: " + target);
            files.put(INDEX, existing);
        }
        repository.put(target, files);
    }

    /** Validate released source, catalog artifacts, substrate pin, contract closure and frozen payloads as one immutable association. */
    private void validateRelease(AssemblyIndex index, Map<String, byte[]> files) throws IOException {
        require(index.kind().equals("release") && index.tenant() == null && index.environment() == null
                && index.revision() == null && index.releaseManifestHash() == null, "invalid release descriptor scope");
        family(index.family()); name(index.profile()); version(index.releaseVersion());
        AssemblySource source = source(files, index.profile());
        require(source.claim().identity().uri().toString().equals(index.uri())
                && source.claim().identity().version().equals(index.claimVersion()), "published source identity mismatch");
        SignedFabricContract signed = signed(required(files, "compiled/signed.yaml"));
        CompiledContract compiled = signed.contract();
        Map<URI, CompositionContract> contracts = contracts(compiled);
        Set<String> expected = new HashSet<>(Set.of("source/definition.yaml", "source/profiles/" + index.profile() + ".yaml",
                "compiled/signed.yaml", "compiled/substrate.yaml"));
        for (CompositionContract contract : contracts.values()) {
            String path = contractPath(contract.contractId()); expected.add(path);
            FrozenContractEnvelope frozen = AssemblyCodec.JSON.readValue(required(files, path), FrozenContractEnvelope.class);
            require(new OfflineContractVerifier().verify(frozen.signedContract(), keys).valid()
                    && Arrays.equals(AssemblyCodec.bytes(contract), frozen.signedContract().canonicalBytes())
                    && Arrays.equals(AssemblyCodec.bytes(contract), AssemblyCodec.bytes(frozen.contract())), "frozen contract mismatch: " + path);
        }
        SubstrateProfileCodec profileCodec = new SubstrateProfileCodec();
        var substrate = profileCodec.parse(required(files, "compiled/substrate.yaml"));
        require(Objects.equals(compiled.substrateProfileHash(), profileCodec.computeProfileHash(substrate))
                && Objects.equals(substrate.profileHash(), compiled.substrateProfileHash()), "substrate profile hash mismatch");
        List<CatalogEntry> selected = new ArrayList<>();
        Set<String> coordinates = new HashSet<>();
        require(!compiled.selections().isEmpty(), "release requires selected artifacts");
        for (var selection : compiled.selections()) {
            require(coordinates.add(selection.artifact().coordinates()), "duplicate selected artifact coordinates");
            String path = "artifacts/" + selection.artifact().sha256() + ".jar"; expected.add(path);
            byte[] jar = required(files, path);
            require(hash(jar).equals(selection.artifact().sha256()), "selected artifact hash mismatch");
            CatalogEntry entry = entry(jar);
            require(entry.artifact().coordinates().equals(selection.artifact().coordinates())
                    && entry.claimDescriptor().claimHash().equals(selection.claimHash()), "selected claim pin mismatch");
            selected.add(entry);
        }
        require(expected.equals(index.files().keySet()), "unexpected or missing release payloads");
        require(new CandidateValidator().validate(selected, source.need()).isValid(), "selected realization does not satisfy source profile");
        Set<String> offers = new HashSet<>();
        Set<String> expectedEdges = new HashSet<>();
        for (CatalogEntry entry : selected) for (var offer : entry.claimDescriptor().claim().offers()) {
            var identity = entry.claimDescriptor().claim().identity();
            expectedEdges.add(identity.uri() + "|" + identity.version() + "|" + offer.capability() + "|" + offer.version());
        }
        Set<String> actualEdges = new HashSet<>();
        for (CompositionContract contract : contracts.values()) if (contract.metadata() == null || contract.metadata().childContractIds().isEmpty()) {
            offers.add(contract.binding().providerCapability());
            var provider = contract.parties().provider();
            require(actualEdges.add(provider.claimUri() + "|" + provider.claimVersion() + "|" + contract.binding().providerCapability()
                    + "|" + contract.binding().providerCapabilityVersion()), "duplicate capability edge");
        }
        require(actualEdges.equals(expectedEdges), "compiled capability edges differ from selected claims");
        for (var offer : source.claim().offers())
            require(contracts.values().stream().anyMatch(c -> c.binding().providerCapability().equals(offer.capability())
                    && c.binding().providerCapabilityVersion().equals(offer.version())), "published offer has no matching compiled realization");
        boolean flow = offers.contains("workflow.execute"), foundry = offers.contains("agent.run");
        require(switch (index.family()) {
            case "flow-only" -> flow && !foundry;
            case "foundry-only" -> foundry && !flow;
            default -> flow && foundry;
        }, "compiled runtime capabilities do not match family");
    }

    /** Validate every runtime binding against the released closure, including exact edge coverage, environment and no-inline-secret firewall. */
    private void validateBindings(AssemblyIndex index, byte[] bytes, Bundle release) throws IOException {
        require(index.claimVersion().equals(release.index().claimVersion()) && index.family().equals(release.index().family())
                && index.profile().equals(release.index().profile()), "deployment release association mismatch");
        JsonNode wrapper = AssemblyCodec.YAML.readTree(bytes);
        require(wrapper.isObject() && wrapper.size() == 1 && wrapper.has("runtime_binding_set"), "runtime_binding_set envelope required");
        JsonNode set = wrapper.get("runtime_binding_set");
        require(set.hasNonNull("root_binding_id") && set.path("runtime_bindings").isArray(), "binding root and inventory required");
        var fields = set.fieldNames();
        while (fields.hasNext()) require(Set.of("root_binding_id", "generated_at", "source_signed_contract", "runtime_bindings").contains(fields.next()), "unknown binding-set field");
        Map<URI, RuntimeBinding> bindings = new HashMap<>();
        Set<URI> edges = new HashSet<>();
        for (JsonNode node : set.get("runtime_bindings")) {
            RuntimeBinding binding = AssemblyCodec.JSON.treeToValue(node, RuntimeBinding.class);
            require(binding.bindingId() != null && bindings.putIfAbsent(binding.bindingId(), binding) == null, "duplicate/missing binding id");
            require(binding.contractId() != null && edges.add(binding.contractId()), "duplicate/missing binding contract");
            require(binding.targetEnvironment() != null && index.environment().equals(binding.targetEnvironment().name()), "binding target environment mismatch");
            require(binding.providerInstance() != null && binding.consumerInstance() != null && binding.runtimePolicy() != null, "binding instances and policy required");
        }
        SignedFabricContract signed = signed(required(release.files(), "compiled/signed.yaml"));
        Map<URI, CompositionContract> contracts = contracts(signed.contract());
        RuntimeBinding root = bindings.get(URI.create(set.get("root_binding_id").asText()));
        require(root != null && signed.contract().contract().contractId().equals(root.contractId()), "binding root contract mismatch");
        require(edges.equals(contracts.keySet()), "binding set must cover exactly the released contracts");
        require(new RuntimeBindingValidator().validateTree(root, bindings, contracts).valid(), "DCP runtime binding validation failed");
        Set<URI> reachable = new HashSet<>();
        walkBindings(root.bindingId(), bindings, reachable);
        require(reachable.equals(bindings.keySet()), "orphan runtime bindings");
    }

    /** Closure validator: use DCP recursive validation, then reject unreferenced compiled contracts. */
    private Map<URI, CompositionContract> contracts(CompiledContract compiled) throws IOException {
        Map<URI, CompositionContract> contracts = new TreeMap<>();
        contracts.put(compiled.contract().contractId(), compiled.contract());
        for (var child : compiled.childContracts()) require(contracts.putIfAbsent(child.contractId(), child) == null, "duplicate contract id");
        require(new ContractValidator().validateTree(compiled.contract(), contracts).valid(), "invalid DCP contract closure");
        Set<URI> visited = new HashSet<>(); walkContracts(compiled.contract().contractId(), contracts, visited);
        require(visited.equals(contracts.keySet()), "orphan compiled contracts");
        return contracts;
    }

    /** Traverse a DCP-validated acyclic contract closure to detect unreferenced records. */
    private void walkContracts(URI id, Map<URI, CompositionContract> contracts, Set<URI> visited) {
        if (!visited.add(id)) return;
        var metadata = contracts.get(id).metadata();
        if (metadata != null) for (URI child : metadata.childContractIds()) walkContracts(child, contracts, visited);
    }

    /** Traverse a DCP-validated binding closure to detect unreferenced records. */
    private void walkBindings(URI id, Map<URI, RuntimeBinding> bindings, Set<URI> visited) {
        if (!visited.add(id)) return;
        var metadata = bindings.get(id).metadata();
        if (metadata != null) for (URI child : metadata.childBindingIds()) walkBindings(child, bindings, visited);
    }

    /** Signature gate: existing Fabric support-envelope verification pins the complete compiled closure. */
    private SignedFabricContract signed(byte[] bytes) throws IOException {
        SignedFabricContract signed = new SignedFabricContractCodec().parse(bytes);
        require(new SignatureVerifier().verify(signed, keys).ok(), "compiled signature verification failed");
        return signed;
    }

    /** Recover a catalog entry from exact retained JAR bytes using the existing manifest and claim hashing conventions. */
    private CatalogEntry entry(byte[] bytes) throws IOException {
        byte[] manifest = null;
        try (JarInputStream jar = new JarInputStream(new ByteArrayInputStream(bytes))) {
            for (var item = jar.getNextJarEntry(); item != null; item = jar.getNextJarEntry()) {
                if (item.getName().equals("META-INF/unfurl-catalog.yaml")) {
                    require(manifest == null, "duplicate catalog manifest in artifact"); manifest = jar.readAllBytes();
                }
            }
        }
        require(manifest != null, "artifact has no catalog manifest");
        CatalogManifestCodec codec = new CatalogManifestCodec(); ParsedManifest parsed = codec.parse(manifest);
        var artifact = parsed.authoredArtifact();
        return new CatalogEntry(new ArtifactDescriptor(artifact.coordinates(), artifact.packaging(), artifact.source(), hash(bytes), artifact.signature()),
                new ClaimDescriptor(parsed.claim(), codec.computeClaimHash(parsed.claim())),
                new CatalogMetadata(parsed.lifecycle(), parsed.binding()), parsed.componentShapeProfile(), null);
    }

    /** Decode the two required source artifacts without relying on the publisher's live filesystem during verification. */
    private AssemblySource source(Map<String, byte[]> files, String profile) throws IOException {
        return AssemblySource.parse(required(files, "source/definition.yaml"), required(files, "source/profiles/" + profile + ".yaml"));
    }

    /** Required payload lookup: report incomplete bundles explicitly rather than deserialize null. */
    private static byte[] required(Map<String, byte[]> files, String path) throws IOException {
        byte[] bytes = files.get(path); require(bytes != null, "missing release payload: " + path); return bytes;
    }

    /** Source read guard: refuse link ancestors and non-files before snapshotting exact bytes. */
    public static byte[] readSource(Path path) throws IOException {
        for (Path cursor = path.toAbsolutePath().normalize(); cursor != null; cursor = cursor.getParent())
            require(!Files.isSymbolicLink(cursor), "source links are forbidden: " + cursor);
        require(Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS), "source file missing: " + path);
        return Files.readAllBytes(path);
    }

    /** Contract path builder: URI hashes avoid unsafe filesystem characters and preserve one file per governed node. */
    private static String contractPath(URI id) { return "contracts/" + hash(id.toString().getBytes(StandardCharsets.UTF_8)) + ".json"; }

    /** Digest projector for immutable payload inventories; signatures are added only after inventory hashing. */
    private static Map<String, String> hashes(Map<String, byte[]> files) {
        Map<String, String> hashes = new TreeMap<>(); files.forEach((path, bytes) -> hashes.put(path, hash(bytes))); return hashes;
    }

    /** Hash utility: reuse Fabric's existing SHA-256 implementation and lowercase-hex representation. */
    private static String hash(byte[] bytes) { return CatalogManifestCodec.sha256Hex(bytes); }

    /** Value Object: authenticated index plus byte-verified payloads scoped to one service operation. */
    private record Bundle(AssemblyIndex index, Map<String, byte[]> files) { }
}
