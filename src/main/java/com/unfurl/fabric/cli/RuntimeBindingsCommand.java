package com.unfurl.fabric.cli;

import com.unfurl.fabric.catalog.CatalogScanner;
import com.unfurl.fabric.catalog.SelectedCatalogEntries;
import com.unfurl.fabric.runtime.DocumentationOptions;
import com.unfurl.fabric.runtime.RuntimeBindingSetGenerator;
import com.unfurl.fabric.runtime.ServiceBaseUrls;
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
 * DCP binding construction and validation to {@link RuntimeBindingSetGenerator}. {@link DocumentationCheckCommand}
 * reads its inputs through {@link #inputs}, so both commands take the same flags.
 */
final class RuntimeBindingsCommand {
    /**
     * Execute `fabric runtime-bindings --signed-contract --catalog --out ...`. {@code --catalog} is the catalog the contract
     * was compiled from, and is always required (HLD-api-documentation-artifacts §4.0). A composition whose needs required
     * identity-context also needs every {@code --identity-*} flag of HLD-needs-driven-identity §6, in any environment; the
     * generator refuses the run otherwise.
     *
     * @param argv command-line flags.
     * @param out command output stream.
     * @return process exit code.
     */
    int run(String[] argv, PrintStream out) {
        CliArgs args = CliArgs.parse(argv);
        Path outPath = args.requiredPath("out");
        Inputs inputs = inputs(args);
        try {
            byte[] yaml = new RuntimeBindingSetGenerator().generate(inputs.signed(), inputs.selected(), inputs.options());
            if (outPath.getParent() != null) {
                Files.createDirectories(outPath.getParent());
            }
            Files.write(outPath, yaml);
            out.println("runtimeBinding=" + outPath);
            out.println("runtimeBindingSha256=" + sha256(yaml));
            out.println("bindings=" + (1 + inputs.signed().contract().childContracts().size()));
            return 0;
        } catch (IOException ex) {
            throw FabricCliException.runtime("unable to write runtime bindings: " + ex.getMessage());
        }
    }

    /**
     * Value Object: everything a binding command reads: the signed contract, its selections resolved in the catalog, and
     * the generator options.
     */
    record Inputs(SignedFabricContract signed, SelectedCatalogEntries selected, RuntimeBindingSetGenerator.Options options) { }

    /**
     * Reads the flags shared by {@code runtime-bindings} and {@code documentation-check}: the signed contract, the
     * required catalog (scanned and resolved against the contract's pins), and every option.
     */
    Inputs inputs(CliArgs args) {
        Path signedPath = args.requiredPath("signed-contract");
        Path catalog = args.optionalPath("catalog");
        if (catalog == null) {
            throw FabricCliException.usage("--catalog is required: it is the catalog the contract was compiled from, which "
                    + "holds the selected providers' claims and catalog blocks (HLD-api-documentation-artifacts §4.0)");
        }
        try {
            byte[] signedBytes = Files.readAllBytes(signedPath);
            SignedFabricContract signed = new SignedFabricContractCodec().parse(signedBytes);
            SelectedCatalogEntries selected;
            try {
                selected = SelectedCatalogEntries.resolve(signed.contract(), new CatalogScanner().scan(catalog).catalog());
            } catch (SelectedCatalogEntries.Refusal refusal) {
                throw FabricCliException.usage("--catalog does not hold the contract's selections: " + refusal.getMessage());
            }
            return new Inputs(signed, selected, options(args, signedPath, signed, signedBytes));
        } catch (IOException ex) {
            throw FabricCliException.runtime("unable to read the signed contract " + signedPath + ": " + ex.getMessage());
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
                serviceBaseUrls(args),
                intValue(args, "timeout-ms", defaults.timeoutMs()),
                identity(args),
                componentConfiguration(args, signed),
                documentation(args));
    }

    /**
     * Service base URLs (HLD-api-documentation-artifacts §4.1): every {@code --service-base-url claimUri=url} (repeatable
     * or comma-separated) plus the {@code --flow-base-url} and {@code --foundry-base-url} aliases.
     */
    private java.util.Map<java.net.URI, String> serviceBaseUrls(CliArgs args) {
        try {
            return ServiceBaseUrls.parse(args.all("service-base-url"), args.get("flow-base-url"), args.get("foundry-base-url"));
        } catch (IllegalArgumentException invalid) {
            throw FabricCliException.usage(invalid.getMessage());
        }
    }

    /**
     * The API catalog's documentation settings (HLD-api-documentation-artifacts §4.2): {@code --documentation-visibility-default},
     * {@code --documentation-visibility <file>}, {@code --catalog-observation-interval-ms} and
     * {@code --catalog-observation-max-backoff-ms}. Whether the composition may take them is the generator's decision.
     */
    private DocumentationOptions documentation(CliArgs args) {
        try {
            return DocumentationOptions.of(args.get("documentation-visibility-default"), args.optionalPath("documentation-visibility"),
                    longValue(args, "catalog-observation-interval-ms"), longValue(args, "catalog-observation-max-backoff-ms"));
        } catch (IllegalArgumentException invalid) {
            throw FabricCliException.usage(invalid.getMessage());
        }
    }

    /** Optional long flag helper: null when absent. */
    private Long longValue(CliArgs args, String key) {
        String value = args.get(key);
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Long.parseLong(value.trim());
        } catch (NumberFormatException ex) {
            throw FabricCliException.usage("--" + key + " must be an integer");
        }
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
                args.get("identity-client-credentials-ref"),
                pairs(args.get("identity-service-credentials"), "--identity-service-credentials", "claimUri=secret://..."));
    }

    /**
     * Parser for {@code --identity-audiences claimUri=audience,...}. Claim URIs contain colons but never {@code =} or
     * {@code ,}, so the split is unambiguous. A malformed or repeated entry is a usage error rather than being skipped.
     */
    private Map<String, String> audiences(String value) {
        return pairs(value, "--identity-audiences", "claimUri=audience");
    }

    /**
     * Parser for a {@code claimUri=value,...} flag ({@code --identity-audiences}, {@code --identity-service-credentials}).
     * Claim URIs and the values ({@code secret://} references, audiences) never contain {@code ,}, and claim URIs never
     * {@code =}, so splitting at the first {@code =} is unambiguous. A malformed or repeated entry is a usage error.
     */
    private Map<String, String> pairs(String value, String flag, String shape) {
        Map<String, String> pairs = new LinkedHashMap<>();
        if (value == null || value.isBlank()) {
            return pairs;
        }
        for (String entry : value.split(",")) {
            int separator = entry.indexOf('=');
            String service = separator < 0 ? "" : entry.substring(0, separator).trim();
            String paired = separator < 0 ? "" : entry.substring(separator + 1).trim();
            if (service.isEmpty() || paired.isEmpty()) {
                throw FabricCliException.usage(flag + " entries must be " + shape + ": " + entry.trim());
            }
            if (pairs.putIfAbsent(service, paired) != null) {
                throw FabricCliException.usage(flag + " names " + service + " more than once");
            }
        }
        return pairs;
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
