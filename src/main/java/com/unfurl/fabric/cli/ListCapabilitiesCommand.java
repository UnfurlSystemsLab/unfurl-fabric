package com.unfurl.fabric.cli;

import com.unfurl.fabric.catalog.CatalogEntry;

import java.io.PrintStream;

final class ListCapabilitiesCommand {
    int run(String[] argv, PrintStream out) {
        CliArgs args = CliArgs.parse(argv);
        for (CatalogEntry entry : CliSupport.scan(args).catalog().entries()) {
            out.println(entry.artifact().coordinates() + " -> [" + CliSupport.entryCapabilities(entry) + "]");
        }
        return 0;
    }
}
