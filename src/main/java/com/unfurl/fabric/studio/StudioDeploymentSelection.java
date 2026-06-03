package com.unfurl.fabric.studio;

import com.unfurl.deployment.domain.DeploymentShape;

import java.util.List;

public record StudioDeploymentSelection(
        String componentId,
        String artifactCoordinates,
        String capability,
        DeploymentShape deploymentShape,
        List<String> requiredPorts) {

    public StudioDeploymentSelection {
        requiredPorts = requiredPorts == null ? List.of() : List.copyOf(requiredPorts);
    }
}
