package com.unfurl.fabric.cli;

import java.io.PrintStream;
import java.util.Arrays;

public final class FabricCli {
    public static void main(String[] args) {
        System.exit(run(args, System.out, System.err));
    }

    public static int run(String[] args, PrintStream out, PrintStream err) {
        try {
            if (args == null || args.length == 0) {
                throw FabricCliException.usage("missing command");
            }
            String command = args[0];
            String[] rest = Arrays.copyOfRange(args, 1, args.length);
            return switch (command) {
                case "scan" -> new ScanCommand().run(rest, out);
                case "list-capabilities" -> new ListCapabilitiesCommand().run(rest, out);
                case "plan" -> new PlanCommand().run(rest, out);
                case "compile" -> new CompileCommand().run(rest, out, err);
                case "dry-run" -> new DryRunCommand().run(rest, out, err);
                case "sign" -> new SignCommand().run(rest, out);
                case "verify" -> new VerifyCommand().run(rest, out, err);
                case "explain" -> new ExplainCommand().run(rest, out);
                case "explain-substrate" -> new ExplainSubstrateCommand().run(rest, out);
                case "diff" -> new DiffCommand().run(rest, out);
                case "explain-rejection" -> new ExplainRejectionCommand().run(rest, out);
                case "analyze-workflow" -> new AnalyzeWorkflowCommand().run(rest, out);
                default -> throw FabricCliException.usage("unknown command: " + command);
            };
        } catch (FabricCliException ex) {
            err.println(ex.getMessage());
            return ex.exitCode();
        } catch (RuntimeException ex) {
            err.println(ex.getMessage());
            return 1;
        }
    }

    private FabricCli() {
    }
}
