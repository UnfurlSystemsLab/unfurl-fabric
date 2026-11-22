package com.unfurl.fabric.cli;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.unfurl.fabric.runtime.DocumentationCheck;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * CLI Adapter: {@code fabric documentation-check}, the export-time documentation check and Flowfoundry Step 18
 * {@code fabric.documentation-check} (HLD-api-documentation-artifacts §6).
 *
 * <p>Pattern: command adapter. It takes the same flags as {@code runtime-bindings} ({@link RuntimeBindingsCommand#inputs}),
 * so it checks the bindings that command would write, and delegates the check to {@link DocumentationCheck}. It writes the
 * report and no binding, and exits 0 on {@code PASS} and 1 on {@code GAP}.
 */
final class DocumentationCheckCommand {
    /**
     * Execute {@code fabric documentation-check --signed-contract --catalog --out <report.json> ...}.
     *
     * @param argv command-line flags
     * @param out  command output stream
     * @return 0 when no gap was found, 1 otherwise
     */
    int run(String[] argv, PrintStream out) {
        CliArgs args = CliArgs.parse(argv);
        Path outPath = args.requiredPath("out");
        RuntimeBindingsCommand.Inputs inputs = new RuntimeBindingsCommand().inputs(args);
        DocumentationCheck.Report report = new DocumentationCheck().run(inputs.signed(), inputs.selected(), inputs.options());
        try {
            Path parent = outPath.toAbsolutePath().getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Files.write(outPath, new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT).writeValueAsBytes(report));
        } catch (IOException ex) {
            throw FabricCliException.runtime("unable to write the documentation check report " + outPath + ": " + ex.getMessage());
        }
        out.println("documentationCheck=" + report.status());
        out.println("gaps=" + report.gaps().size());
        report.gaps().forEach(gap -> out.println("gap " + gap.contractId() + " " + gap.capability() + " " + gap.provider()));
        out.println("report=" + outPath);
        return DocumentationCheck.PASS.equals(report.status()) ? 0 : 1;
    }
}
