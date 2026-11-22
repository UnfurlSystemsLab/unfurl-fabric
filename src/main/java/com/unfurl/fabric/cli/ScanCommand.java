package com.unfurl.fabric.cli;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.unfurl.fabric.catalog.CatalogScanReport;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;

final class ScanCommand {
    int run(String[] argv, PrintStream out) {
        CliArgs args = CliArgs.parse(argv);
        CatalogScanReport report = CliSupport.scan(args);
        out.println("entries=" + report.catalog().entries().size());
        out.println("companions=" + report.catalog().companions().size());
        out.println("skipped=" + report.skippedEntries().size());
        // Skips and warnings are named, so nothing is excluded or doubted silently (HLD-api-documentation-artifacts §2).
        report.skippedEntries().forEach(skip -> out.println("skip " + skip.jarPath().getFileName() + " " + skip.reason()
                + ": " + skip.detail()));
        report.warnings().forEach(warning -> out.println("warning " + warning.jarPath().getFileName() + " " + warning.code()
                + ": " + warning.detail()));
        Path target = args.optionalPath("out");
        if (target != null) {
            try {
                Path parent = target.toAbsolutePath().getParent();
                if (parent != null) {
                    Files.createDirectories(parent);
                }
                new ObjectMapper(new YAMLFactory()).writeValue(target.toFile(), report.catalog());
            } catch (IOException ex) {
                throw FabricCliException.runtime("unable to write catalog index " + target + ": " + ex.getMessage());
            }
            out.println("wrote=" + target);
        }
        return 0;
    }
}
