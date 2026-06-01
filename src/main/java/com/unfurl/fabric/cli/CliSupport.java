package com.unfurl.fabric.cli;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.fasterxml.jackson.dataformat.yaml.YAMLGenerator;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.unfurl.fabric.catalog.Catalog;
import com.unfurl.fabric.catalog.CatalogEntry;
import com.unfurl.fabric.catalog.CatalogScanReport;
import com.unfurl.fabric.catalog.CatalogScanner;
import com.unfurl.fabric.compiler.CompiledContract;
import com.unfurl.fabric.compiler.CompiledContractCodec;
import com.unfurl.fabric.compiler.ContractCompiler;
import com.unfurl.fabric.compiler.HostOwnerMeta;
import com.unfurl.fabric.matcher.CompositionCandidate;
import com.unfurl.fabric.matcher.MatchResult;
import com.unfurl.fabric.matcher.StructuralMatcher;
import com.unfurl.fabric.needs.Need;
import com.unfurl.fabric.needs.NeedsCodec;
import com.unfurl.fabric.substrate.SubstrateProfileDeriver;
import com.unfurl.fabric.trust.TrustClassification;
import com.unfurl.fabric.trust.TrustClassifier;
import com.unfurl.fabric.trust.TrustPolicy;
import com.unfurl.substrate.api.SubstrateProfile;
import com.unfurl.substrate.api.SubstrateProfileCodec;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.stream.Collectors;

final class CliSupport {
    static final Clock CLI_COMPILE_CLOCK = Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC);

    static CatalogScanReport scan(CliArgs args) {
        return new CatalogScanner().scan(args.requiredPath("catalog"));
    }

    static Need loadNeed(CliArgs args) {
        return new NeedsCodec().read(args.requiredPath("needs"));
    }

    static TrustPolicy loadTrustPolicy(CliArgs args) {
        Path path = args.optionalPath("trust-policy");
        if (path == null) {
            return TrustPolicy.permissive();
        }
        try {
            return yamlMapper().readValue(path.toFile(), TrustPolicy.class);
        } catch (IOException ex) {
            throw FabricCliException.runtime("unable to read trust policy " + path + ": " + ex.getMessage());
        }
    }

    static Planned planned(CliArgs args) {
        Catalog catalog = scan(args).catalog();
        Need need = loadNeed(args);
        TrustClassification trust = new TrustClassifier().classify(catalog, loadTrustPolicy(args));
        MatchResult result = new StructuralMatcher().match(trust.allowedEntries(), need, trust.rejectedEntries());
        return new Planned(catalog, need, trust, result);
    }

    static CompiledWithProfile compileCandidate(CompositionCandidate candidate, Need need, String selectionMode) {
        CompiledContract compiled = new ContractCompiler(CLI_COMPILE_CLOCK)
                .compile(candidate, need, new HostOwnerMeta(null, null, null));
        SubstrateProfile profile = new SubstrateProfileDeriver().derive(candidate);
        SubstrateProfileCodec profileCodec = new SubstrateProfileCodec();
        SubstrateProfile hashedProfile = profile.withProfileHash(profileCodec.computeProfileHash(profile));
        CompiledContract withProfileHash = new CompiledContract(
                compiled.contract(),
                compiled.selections(),
                compiled.audit().withSelectionMode(selectionMode),
                hashedProfile.profileHash(),
                compiled.signature());
        return new CompiledWithProfile(withProfileHash, hashedProfile);
    }

    static void writeCompiled(CompiledContract compiled, Path out) {
        try {
            Path parent = out.toAbsolutePath().getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Files.write(out, new CompiledContractCodec().write(compiled));
        } catch (IOException ex) {
            throw FabricCliException.runtime("unable to write compiled contract " + out + ": " + ex.getMessage());
        }
    }

    static Path defaultProfilePath(Path contractOut) {
        String fileName = contractOut.getFileName().toString();
        int dot = fileName.lastIndexOf('.');
        String base = dot > 0 ? fileName.substring(0, dot) : fileName;
        Path parent = contractOut.toAbsolutePath().getParent();
        Path profileName = Path.of(base + ".substrate-profile.yaml");
        return parent == null ? profileName : parent.resolve(profileName);
    }

    static void writeProfile(SubstrateProfile profile, Path out) {
        try {
            Path parent = out.toAbsolutePath().getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Files.write(out, new SubstrateProfileCodec().write(profile));
        } catch (IOException ex) {
            throw FabricCliException.runtime("unable to write substrate profile " + out + ": " + ex.getMessage());
        }
    }

    static CompiledContract readCompiled(Path path) {
        try {
            return new CompiledContractCodec().parse(Files.readAllBytes(path));
        } catch (IOException ex) {
            throw FabricCliException.runtime("unable to read compiled contract " + path + ": " + ex.getMessage());
        }
    }

    static String candidateLine(CompositionCandidate candidate) {
        return candidate.candidateId() + " score=" + candidate.score().finalScore()
                + " entries=" + candidate.entries().stream()
                .map(e -> e.artifact().coordinates())
                .collect(Collectors.joining(","));
    }

    static String entryCapabilities(CatalogEntry entry) {
        return entry.claimDescriptor().claim().offers().stream()
                .map(o -> o.capability() + ":" + o.version())
                .collect(Collectors.joining(", "));
    }

    private static ObjectMapper yamlMapper() {
        YAMLFactory yamlFactory = new YAMLFactory()
                .disable(YAMLGenerator.Feature.WRITE_DOC_START_MARKER)
                .enable(YAMLGenerator.Feature.MINIMIZE_QUOTES);
        ObjectMapper mapper = new ObjectMapper(yamlFactory);
        mapper.registerModule(new JavaTimeModule());
        mapper.disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
        mapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        mapper.setSerializationInclusion(JsonInclude.Include.NON_NULL);
        return mapper;
    }

    record Planned(Catalog catalog, Need need, TrustClassification trust, MatchResult result) {
    }

    record CompiledWithProfile(CompiledContract contract, SubstrateProfile profile) {
    }

    private CliSupport() {
    }
}
