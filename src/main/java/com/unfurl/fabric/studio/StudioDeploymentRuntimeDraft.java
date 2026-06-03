package com.unfurl.fabric.studio;

public record StudioDeploymentRuntimeDraft(
        String javaVersion,
        Boolean springBoot,
        Boolean kubernetes,
        Boolean serviceMesh,
        Integer maxServices) {
}
