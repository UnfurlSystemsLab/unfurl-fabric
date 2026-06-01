package com.unfurl.fabric.cli;

import com.unfurl.fabric.catalog.Catalog;
import com.unfurl.fabric.needs.Need;
import com.unfurl.fabric.trust.RejectedEntry;
import com.unfurl.fabric.trust.TrustClassification;
import com.unfurl.fabric.trust.TrustClassifier;

import java.io.PrintStream;
import java.util.stream.Collectors;

final class ExplainRejectionCommand {
    int run(String[] argv, PrintStream out) {
        CliArgs args = CliArgs.parse(argv);
        Catalog catalog = CliSupport.scan(args).catalog();
        // Keep --needs required for command symmetry with plan, even though trust classification
        // itself does not inspect needs yet.
        Need ignored = CliSupport.loadNeed(args);
        if (ignored == null) {
            throw FabricCliException.usage("missing --needs");
        }
        TrustClassification classification = new TrustClassifier().classify(catalog, CliSupport.loadTrustPolicy(args));
        for (RejectedEntry rejected : classification.rejectedEntries()) {
            out.println(rejected.entry().artifact().coordinates() + ": "
                    + rejected.reasons().stream()
                    .map(r -> r.detail())
                    .collect(Collectors.joining("; ")));
        }
        return 0;
    }
}
