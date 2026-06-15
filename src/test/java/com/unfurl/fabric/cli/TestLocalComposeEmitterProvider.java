package com.unfurl.fabric.cli;

import com.unfurl.deploy.spi.DeployEmitter;
import com.unfurl.deploy.spi.EmitterConfig;
import com.unfurl.deploy.spi.EmitterProvider;

public final class TestLocalComposeEmitterProvider implements EmitterProvider {

    @Override
    public String targetKind() {
        return "local-compose";
    }

    @Override
    public DeployEmitter create(EmitterConfig config) {
        return new TestDeployEmitter(config);
    }
}
