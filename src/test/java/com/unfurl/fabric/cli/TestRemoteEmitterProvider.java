package com.unfurl.fabric.cli;

import com.unfurl.deploy.spi.DeployEmitter;
import com.unfurl.deploy.spi.EmitterConfig;
import com.unfurl.deploy.spi.EmitterProvider;

public final class TestRemoteEmitterProvider implements EmitterProvider {

    @Override
    public String targetKind() {
        return "remote";
    }

    @Override
    public DeployEmitter create(EmitterConfig config) {
        return new TestDeployEmitter(config);
    }
}
