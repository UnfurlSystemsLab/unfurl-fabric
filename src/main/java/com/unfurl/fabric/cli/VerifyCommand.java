package com.unfurl.fabric.cli;

import com.unfurl.dcp.trust.VerificationKeySet;
import com.unfurl.fabric.catalog.Catalog;
import com.unfurl.fabric.signing.SignedFabricContract;
import com.unfurl.fabric.signing.SignedFabricContractCodec;
import com.unfurl.fabric.verify.CatalogDriftChecker;
import com.unfurl.fabric.verify.CatalogDriftReport;
import com.unfurl.fabric.verify.FabricVerificationReport;
import com.unfurl.fabric.signing.SignatureVerificationResult;
import com.unfurl.fabric.signing.SignatureVerifier;
import com.unfurl.fabric.verify.TrustKeyDirectoryLoader;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;

final class VerifyCommand {
    int run(String[] argv, PrintStream out, PrintStream err) {
        CliArgs args = CliArgs.parse(argv);
        Path signedPath = args.requiredPath("contract");
        Path trustKeys = args.requiredPath("trust-keys");
        try {
            SignedFabricContract signed = new SignedFabricContractCodec()
                    .parse(Files.readAllBytes(signedPath));
            VerificationKeySet keys = new TrustKeyDirectoryLoader().load(trustKeys);
            SignatureVerificationResult signature = new SignatureVerifier().verify(signed, keys);
            FabricVerificationReport report;
            Path catalogPath = args.optionalPath("catalog");
            if (catalogPath == null) {
                report = FabricVerificationReport.signatureOnly(signature);
            } else {
                Catalog catalog = new com.unfurl.fabric.catalog.CatalogScanner().scan(catalogPath).catalog();
                CatalogDriftReport drift = new CatalogDriftChecker().check(signed.contract(), catalog);
                report = FabricVerificationReport.of(signature, drift);
            }
            print(report, out);
            if (!report.overallOk()) {
                err.println("verification failed");
                return 1;
            }
            return 0;
        } catch (IOException ex) {
            throw FabricCliException.runtime("unable to read signed contract " + signedPath + ": " + ex.getMessage());
        }
    }

    private static void print(FabricVerificationReport report, PrintStream out) {
        out.println("signature=" + report.signature().getClass().getSimpleName());
        out.println("signatureOk=" + report.signature().ok());
        out.println("catalogDriftPresent=" + report.catalogDrift().isPresent());
        report.catalogDrift().ifPresent(d -> {
            out.println("catalogClean=" + d.clean());
            out.println("hashDrifts=" + d.hashDrifts().size());
            out.println("missingFromCatalog=" + d.missingFromCatalog().size());
        });
        out.println("overallOk=" + report.overallOk());
    }
}
