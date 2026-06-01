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
        return 0;
    }

    private static void createParent(Path target) throws IOException {
        Path parent = target.toAbsolutePath().getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
    }
}
