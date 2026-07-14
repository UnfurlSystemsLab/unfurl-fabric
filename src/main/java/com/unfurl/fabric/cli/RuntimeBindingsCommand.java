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

/**
 * CLI Adapter: turns a signed Fabric contract closure into a DCP runtime-binding set.
 *
 * <p>Pattern: command adapter. The command owns filesystem argument parsing and delegates all
 * DCP binding construction and validation to {@link RuntimeBindingSetGenerator}.
 */
final class RuntimeBindingsCommand {
    /**
     * Execute `fabric runtime-bindings --signed-contract --out ...`.
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
                intValue(args, "timeout-ms", defaults.timeoutMs()));
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
