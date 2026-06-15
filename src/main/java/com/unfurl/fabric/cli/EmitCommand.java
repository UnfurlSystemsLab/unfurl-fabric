package com.unfurl.fabric.cli;

import com.unfurl.deploy.spi.EmitResult;

import java.io.PrintStream;

final class EmitCommand {
    int run(String[] argv, PrintStream out) {
        DeployCliSupport.EmitOutcome outcome = DeployCliSupport.emit(CliArgs.parse(argv));
        DeployCliSupport.printEmit(outcome, out);
        return outcome.result() instanceof EmitResult.Ok ? 0 : 1;
    }
}
