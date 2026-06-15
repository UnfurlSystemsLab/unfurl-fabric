package com.unfurl.fabric.cli;

import com.unfurl.deploy.spi.ApplyResult;
import com.unfurl.deployment.policy.DeploymentTarget;

import java.io.PrintStream;
import java.nio.file.Path;

final class ApplyCommand {
    int run(String[] argv, PrintStream out) {
        CliArgs args = CliArgs.parse(argv);
        Path planDir = args.requiredPath("plan");
        DeploymentTarget target = DeployCliSupport.loadTarget(args.get("target"));
        ApplyResult result = DeployCliSupport.apply(planDir, target, args.has("dry-run"));
        DeployCliSupport.printApply(result, out);
        return result instanceof ApplyResult.Ok ? 0 : 1;
    }
}
