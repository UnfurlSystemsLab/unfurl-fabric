package com.unfurl.fabric.cli;

import com.unfurl.fabric.needs.CapabilityRequirement;
import com.unfurl.fabric.needs.Need;
import com.unfurl.fabric.needs.NeedsCodec;
import com.unfurl.fabric.workflow.WorkflowAnalyzer;
import com.unfurl.fabric.workflow.WorkflowAnalysisException;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;

final class AnalyzeWorkflowCommand {
    int run(String[] argv, PrintStream out) {
        CliArgs args = CliArgs.parse(argv);
        Path workflow = args.requiredPath("workflow");
        Path target = args.optionalPath("out");

        Need suggested;
        try {
            suggested = new WorkflowAnalyzer().analyze(workflow);
        } catch (WorkflowAnalysisException ex) {
            throw FabricCliException.runtime(ex.getMessage());
        }

        if (target != null) {
            new NeedsCodec().write(suggested, target);
            out.println("suggestedNeeds=" + target);
        } else {
            writeToStdout(suggested, out);
        }
        out.println("requiredCapabilities=" + suggested.requiredCapabilities().size());
        suggested.requiredCapabilities().stream()
                .map(CapabilityRequirement::capability)
                .forEach(capability -> out.println("- " + capability));
        return 0;
    }

    private void writeToStdout(Need need, PrintStream out) {
        try {
            Path temp = Files.createTempFile("unfurl-suggested-needs", ".yaml");
            new NeedsCodec().write(need, temp);
            out.print(Files.readString(temp));
            Files.deleteIfExists(temp);
        } catch (IOException ex) {
            throw FabricCliException.runtime("unable to render suggested needs: " + ex.getMessage());
        }
    }
}
