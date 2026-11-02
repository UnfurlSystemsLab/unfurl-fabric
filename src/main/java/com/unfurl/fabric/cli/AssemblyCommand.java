package com.unfurl.fabric.cli;

import com.unfurl.dcp.trust.SigningKeyRef;
import com.unfurl.fabric.assembly.*;
import com.unfurl.fabric.signing.FabricContractSigner;
import com.unfurl.fabric.signing.SigningKeyLoader;
import com.unfurl.fabric.verify.TrustKeyDirectoryLoader;
import java.io.IOException;
import java.io.PrintStream;
import java.util.Set;
import java.util.Arrays;

/** Command Adapter: exposes source initialization and immutable release/deployment storage without executing or activating workloads. */
final class AssemblyCommand {
    /** Parse one lifecycle operation, delegate through repository/signing ports, and report the concrete stored locator. */
    int run(String command, String[] argv, PrintStream out) {
        try {
            CliArgs args = CliArgs.parse(argv);
            Set<String> allowed = switch (command) {
                case "assembly-init" -> Set.of("workspace", "family", "name", "definition", "needs", "profile");
                case "assembly-publish" -> Set.of("source", "profile", "version", "signed-contract", "substrate-profile", "catalog", "repository", "key", "public-key", "trust-keys");
                case "assembly-verify" -> Set.of("repository", "uri", "version", "trust-keys");
                case "assembly-bind" -> Set.of("repository", "deployment-repository", "uri", "version", "tenant", "environment", "revision", "bindings", "key", "public-key", "trust-keys");
                default -> Set.of("repository", "deployment-repository", "uri", "version", "tenant", "environment", "revision", "trust-keys");
            };
            for (String flag : argv) if (flag.startsWith("--") && !allowed.contains(flag.substring(2)))
                throw FabricCliException.usage("unknown assembly flag: " + flag);
            long flags = Arrays.stream(argv).filter(value -> value.startsWith("--")).count();
            long unique = Arrays.stream(argv).filter(value -> value.startsWith("--")).distinct().count();
            if (flags != unique) throw FabricCliException.usage("duplicate assembly flag");
            if (command.equals("assembly-init")) {
                var service = new AssemblyStorageService(new FileArtifactRepository(args.requiredPath("workspace")),
                        new com.unfurl.dcp.trust.VerificationKeySet(java.util.Map.of()));
                service.initialize(new FileArtifactRepository(args.requiredPath("workspace")), required(args, "family"),
                        required(args, "name"), required(args, "profile"),
                        AssemblyStorageService.readSource(args.requiredPath("definition")), AssemblyStorageService.readSource(args.requiredPath("needs")));
                out.println("source=" + args.requiredPath("workspace").resolve("assemblies").resolve(required(args, "family")).resolve(required(args, "name")));
                return 0;
            }
            var service = new AssemblyStorageService(new FileArtifactRepository(args.requiredPath("repository")),
                    new TrustKeyDirectoryLoader().load(args.requiredPath("trust-keys")));
            AssemblyIndex index;
            if (command.equals("assembly-publish")) {
                var signer = signer(args);
                index = service.publish(args.requiredPath("source"), required(args, "profile"), required(args, "version"),
                        AssemblyStorageService.readSource(args.requiredPath("signed-contract")),
                        AssemblyStorageService.readSource(args.requiredPath("substrate-profile")), args.requiredPath("catalog"),
                        signer, new SigningKeyRef(signer.keyFingerprint()));
            } else if (command.equals("assembly-verify")) {
                index = service.verify(required(args, "uri"), required(args, "version"));
            } else {
                var deployments = new FileArtifactRepository(args.requiredPath("deployment-repository"));
                if (command.equals("assembly-bind")) {
                    var signer = signer(args);
                    index = service.bind(deployments, required(args, "uri"), required(args, "version"), required(args, "tenant"),
                            required(args, "environment"), required(args, "revision"), AssemblyStorageService.readSource(args.requiredPath("bindings")),
                            signer, new SigningKeyRef(signer.keyFingerprint()));
                } else {
                    index = service.verifyBinding(deployments, required(args, "uri"), required(args, "version"), required(args, "tenant"),
                            required(args, "environment"), required(args, "revision"));
                }
            }
            out.println("uri=" + index.uri());
            out.println("releaseVersion=" + index.releaseVersion());
            out.println("bundle=" + (index.kind().equals("release") ? AssemblyStorageService.releaseKey(index.uri(), index.releaseVersion())
                    : AssemblyStorageService.deploymentKey(index.uri(), index.releaseVersion(), index.tenant(), index.environment(), index.revision())));
            out.println("verified=true");
            return 0;
        } catch (IOException ex) {
            throw FabricCliException.runtime("assembly storage failed: " + ex.getMessage());
        }
    }

    /** Required text option guard: fail before path construction or signing on absent locators. */
    private String required(CliArgs args, String key) {
        String value = args.get(key);
        if (value == null || value.isBlank()) throw FabricCliException.usage("missing required --" + key);
        return value;
    }

    /** Signing Adapter factory: reuse existing PEM loading and DCP-compatible Fabric signer. */
    private FabricContractSigner signer(CliArgs args) {
        var privateKey = SigningKeyLoader.loadPrivateKey(args.requiredPath("key"));
        var publicKey = SigningKeyLoader.loadPublicKey(args.requiredPath("public-key"));
        return new FabricContractSigner(privateKey.key(), privateKey.signatureAlgorithm(), publicKey.fingerprint());
    }
}
