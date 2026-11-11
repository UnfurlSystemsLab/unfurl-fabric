package com.unfurl.fabric.cli;

import com.unfurl.fabric.runtime.RuntimeBindingSetGenerator;
import com.unfurl.fabric.signing.SignedFabricContract;
import com.unfurl.fabric.signing.SignedFabricContractCodec;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * CLI Adapter: turns a signed Fabric contract closure into a DCP runtime-binding set.
 *
 * <p>Pattern: command adapter. The command owns filesystem argument parsing and delegates all
 * DCP binding construction and validation to {@link RuntimeBindingSetGenerator}.
 */
final class RuntimeBindingsCommand {
    /**
     * Execute `fabric runtime-bindings --signed-contract --out ...`. A composition whose needs required identity-context also
     * needs every {@code --identity-*} flag of HLD-needs-driven-identity §6, in any environment; the generator refuses the run
     * otherwise.
     *
     * @param argv command-line flags.
     * @param out command output stream.
     * @return process exit code.
     */
    int run(String[] argv, PrintStream out) {
        CliArgs args = CliArgs.parse(argv);
        Path signedPath = args.requiredPath("signed-contract");
        Path outPath = args.requiredPath("out");
        try {
            byte[] signedBytes = Files.readAllBytes(signedPath);
            SignedFabricContract signed = new SignedFabricContractCodec().parse(signedBytes);
            byte[] yaml = new RuntimeBindingSetGenerator().generate(signed, options(args, signedPath, signed, signedBytes));
            if (outPath.getParent() != null) {
                Files.createDirectories(outPath.getParent());
            }
            Files.write(outPath, yaml);
            out.println("runtimeBinding=" + outPath);
            out.println("runtimeBindingSha256=" + sha256(yaml));
            out.println("bindings=" + (1 + signed.contract().childContracts().size()));
            return 0;
        } catch (IOException ex) {
            throw FabricCliException.runtime("unable to write runtime bindings: " + ex.getMessage());
        }
    }

    /**
     * Option factory: maps CLI flags and signed-contract metadata into generator options.
     */
    private RuntimeBindingSetGenerator.Options options(
            CliArgs args,
            Path signedPath,
            SignedFabricContract signed,
            byte[] signedBytes
    ) {
        RuntimeBindingSetGenerator.Options defaults = RuntimeBindingSetGenerator.Options.defaults();
        return new RuntimeBindingSetGenerator.Options(
                signedPath,
                sha256(signedBytes),
                signed.canonicalHash(),
                signed.signerKeyFingerprint(),
                value(args, "tenant", defaults.tenant()),
                value(args, "environment", defaults.environment()),
                value(args, "telemetry-namespace", defaults.telemetryNamespace()),
                value(args, "binding-id-prefix", defaults.bindingIdPrefix()),
                value(args, "flow-base-url", defaults.flowBaseUrl()),
                value(args, "foundry-base-url", defaults.foundryBaseUrl()),
                intValue(args, "timeout-ms", defaults.timeoutMs()),
                identity(args),
                componentConfiguration(args, signed));
    }

    /**
     * Hosted components' deployment configuration (HLD-in-process-component-hosting §6): when the contract hosts
     * in-process components, {@code --catalog} names the catalog their packaged schemas are read from, and every
     * {@code --component-config <claimUri>#<key>=<value>} is validated against them with DCP's validator before any
     * binding is written. A contract without hosted components refuses {@code --component-config}.
     */
    private java.util.Map<java.net.URI, com.unfurl.dcp.runtimebinding.Configuration> componentConfiguration(
            CliArgs args, SignedFabricContract signed) {
        try {
            return com.unfurl.fabric.runtime.HostedComponentConfiguration.forContract(signed.contract(),
                    args.optionalPath("catalog"), args.all("component-config"));
        } catch (com.unfurl.fabric.runtime.HostedComponentConfiguration.Refusal refusal) {
            // The shared rule, phrased with this command's flags.
            throw FabricCliException.usage(switch (refusal.reason()) {
                case CONFIGURATION_WITHOUT_HOSTED -> "--component-config is for hosted components, and this contract hosts none";
                case CATALOG_REQUIRED -> "--catalog is required: the contract hosts " + refusal.getMessage()
                        + ", whose packaged configuration schemas are read from the catalog";
                case INVALID -> "hosted component configuration is invalid: " + refusal.getMessage();
            });
        }
    }

    /**
     * Option factory for the identity-provider binding (HLD-needs-driven-identity §6): maps the {@code --identity-*} flags
     * one to one, with no defaults. Whether the set is complete enough for the composition is the generator's decision.
     */
    private RuntimeBindingSetGenerator.IdentityOptions identity(CliArgs args) {
        return new RuntimeBindingSetGenerator.IdentityOptions(
                args.get("identity-issuer"),
                audiences(args.get("identity-audiences")),
                args.get("identity-tenant-claim"),
                args.get("identity-roles-claim"),
                args.get("identity-permissions-claim"),
                args.get("identity-token-endpoint-ref"),
                args.get("identity-verification-keys-ref"),
                args.get("identity-client-credentials-ref"));
    }

    /**
     * Parser for {@code --identity-audiences claimUri=audience,...}. Claim URIs contain colons but never {@code =} or
     * {@code ,}, so the split is unambiguous. A malformed or repeated entry is a usage error rather than being skipped.
     */
    private Map<String, String> audiences(String value) {
        Map<String, String> audiences = new LinkedHashMap<>();
        if (value == null || value.isBlank()) {
            return audiences;
        }
        for (String entry : value.split(",")) {
            int separator = entry.indexOf('=');
            String service = separator < 0 ? "" : entry.substring(0, separator).trim();
            String audience = separator < 0 ? "" : entry.substring(separator + 1).trim();
            if (service.isEmpty() || audience.isEmpty()) {
                throw FabricCliException.usage("--identity-audiences entries must be claimUri=audience: " + entry.trim());
            }
            if (audiences.putIfAbsent(service, audience) != null) {
                throw FabricCliException.usage("--identity-audiences names " + service + " more than once");
            }
        }
        return audiences;
    }

    /** Optional string flag helper. */
    private String value(CliArgs args, String key, String fallback) {
        String value = args.get(key);
        return value == null || value.isBlank() ? fallback : value;
    }

    /** Optional integer flag helper. */
    private int intValue(CliArgs args, String key, int fallback) {
        String value = args.get(key);
        if (value == null || value.isBlank()) {
            return fallback;
        }
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException ex) {
            throw FabricCliException.usage("--" + key + " must be an integer");
        }
    }

    /** Hash helper: returns a public sha256-prefixed digest for generated artifacts. */
    private String sha256(byte[] bytes) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return "sha256:" + java.util.HexFormat.of().formatHex(digest.digest(bytes));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is unavailable", ex);
        }
    }
}
