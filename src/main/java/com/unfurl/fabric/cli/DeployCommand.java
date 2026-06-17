package com.unfurl.fabric.cli;

import com.unfurl.deploy.core.StitchApplyResult;
import com.unfurl.deploy.spi.ApplyResult;
import com.unfurl.deploy.spi.EmitResult;

import java.io.PrintStream;

final class DeployCommand {
    int run(String[] argv, PrintStream out) {
        CliArgs args = CliArgs.parse(argv);
        if (args.has("targets")) {
            DeployCliSupport.StitchOutcome outcome = DeployCliSupport.stitch(args);
            DeployCliSupport.printStitch(outcome, out);
            if (args.has("apply") || args.has("dry-run")) {
                StitchApplyResult applied = DeployCliSupport.applyStitched(outcome, args.has("dry-run"));
                DeployCliSupport.printStitchApply(applied, out);
                return applied.ok() ? 0 : 1;
            }
            out.println("apply=skipped");
            out.println("reason=stitched apply requires --apply or --dry-run");
            return 0;
        }
        DeployCliSupport.EmitOutcome outcome = DeployCliSupport.emit(args);
        DeployCliSupport.printEmit(outcome, out);
        if (!(outcome.result() instanceof EmitResult.Ok)) {
            return 1;
        }

        boolean shouldApply = args.has("apply") || args.has("dry-run") || !DeployCliSupport.cloudTarget(outcome.target());
        if (!shouldApply) {
            out.println("apply=skipped");
            out.println("reason=cloud target requires --apply");
            return 0;
        }

        ApplyResult result = DeployCliSupport.apply(outcome.outDir(), outcome.target(), args.has("dry-run"));
        DeployCliSupport.printApply(result, out);
        return result instanceof ApplyResult.Ok ? 0 : 1;
    }
}
