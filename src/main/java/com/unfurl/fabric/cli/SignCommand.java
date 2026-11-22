package com.unfurl.fabric.cli;

import com.unfurl.fabric.compiler.CompiledContract;
import com.unfurl.fabric.signing.FabricContractSigner;
import com.unfurl.fabric.signing.FabricSigningException;
import com.unfurl.fabric.signing.SignedFabricContract;
import com.unfurl.fabric.signing.SignedFabricContractCodec;
import com.unfurl.fabric.signing.SigningKeyLoader;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;

final class SignCommand {
    int run(String[] argv, PrintStream out) {
        CliArgs args = CliArgs.parse(argv);
        Path contractPath = args.requiredPath("contract");
        Path privateKeyPath = args.requiredPath("key");
        Path publicKeyPath = args.requiredPath("public-key");
        Path target = args.requiredPath("out");

        CompiledContract contract = CliSupport.readCompiled(contractPath);
        SigningKeyLoader.LoadedPrivateKey privateKey = SigningKeyLoader.loadPrivateKey(privateKeyPath);
        SigningKeyLoader.LoadedPublicKey publicKey = SigningKeyLoader.loadPublicKey(publicKeyPath);
        FabricContractSigner signer = new FabricContractSigner(
                privateKey.key(),
                privateKey.signatureAlgorithm(),
                publicKey.fingerprint());
        SignedFabricContract signed = signer.signCompiledContract(contract);
        try {
            createParent(target);
            Files.write(target, new SignedFabricContractCodec().write(signed));
        } catch (IOException ex) {
            throw new FabricSigningException("unable to write signed contract " + target + ": " + ex.getMessage(), ex);
        }
        out.println("signed=" + target);
        out.println("fingerprint=" + publicKey.fingerprint());
        Path bundle = args.optionalPath("dcp-runtime-bundle");
        if (bundle != null) {
            writeRuntimeBundle(args, contract, signer, publicKey.fingerprint(), bundle);
            out.println("dcpRuntimeBundle=" + bundle);
        }
        return 0;
    }

    /**
     * Writes the DCP runtime bundle with the key that signed the contract (HLD-in-process-component-hosting §7). The
     * bundle carries the selected providers' claims and their documentation artifacts, so {@code --catalog} names the
     * catalog the contract was compiled from; every selection must be found there with its pinned claim
     * ({@link com.unfurl.fabric.catalog.SelectedCatalogEntries}), or nothing is written.
     */
    private void writeRuntimeBundle(CliArgs args, CompiledContract contract, FabricContractSigner signer,
                                    String fingerprint, Path bundle) {
        Path catalog = args.optionalPath("catalog");
        if (catalog == null) {
            throw FabricCliException.usage("--dcp-runtime-bundle needs --catalog, which holds the selected providers' claims");
        }
        java.util.List<com.unfurl.fabric.catalog.CatalogEntry> selected;
        try {
            selected = com.unfurl.fabric.catalog.SelectedCatalogEntries.resolve(contract,
                    new com.unfurl.fabric.catalog.CatalogScanner().scan(catalog).catalog()).entries();
        } catch (com.unfurl.fabric.catalog.SelectedCatalogEntries.Refusal refusal) {
            throw FabricCliException.usage("--catalog does not hold the contract's selections: " + refusal.getMessage());
        }
        try {
            createParent(bundle);
            Files.write(bundle, new com.unfurl.fabric.compile.DcpRuntimeBundleWriter().write(contract, selected, signer, fingerprint));
        } catch (IOException ex) {
            throw new FabricSigningException("unable to write DCP runtime bundle " + bundle + ": " + ex.getMessage(), ex);
        }
    }

    private static void createParent(Path target) throws IOException {
        Path parent = target.toAbsolutePath().getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
    }
}
