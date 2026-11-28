package com.unfurl.fabric.studio;

import com.unfurl.deployment.domain.DeploymentShape;

import java.util.List;

/**
 * Data Transfer Object: one component's selected deployment shape in a {@code POST /studio/deployment/resolve} answer.
 *
 * <p>{@code componentId} is the component's artifact coordinates, as the deployment resolver names it. In session mode
 * {@code catalogEntryId} is the draft's catalog entry the selection was resolved for, so Studio places the shape on that
 * entry's projection node, which carries no coordinates. Filesystem mode has no draft, so it is empty there
 * (LLD-fabric-studio-api, Deployment Resolution).
 */
public record StudioDeploymentSelection(
        String componentId,
        String artifactCoordinates,
        String capability,
        DeploymentShape deploymentShape,
        List<String> requiredPorts,
        String catalogEntryId) {

    /** Invariant constructor: freezes the required ports and reads an absent catalog entry as empty. */
    public StudioDeploymentSelection {
        requiredPorts = requiredPorts == null ? List.of() : List.copyOf(requiredPorts);
        catalogEntryId = catalogEntryId == null ? "" : catalogEntryId;
    }

    /** A selection not yet attributed to a draft catalog entry (filesystem mode, or before session attribution). */
    public StudioDeploymentSelection(
            String componentId,
            String artifactCoordinates,
            String capability,
            DeploymentShape deploymentShape,
            List<String> requiredPorts) {
        this(componentId, artifactCoordinates, capability, deploymentShape, requiredPorts, "");
    }

    /**
     * This selection attributed to the draft catalog entry it was resolved for.
     *
     * @param entryId the draft's catalog entry id, for example {@code uploaded:flow.jar}.
     * @return a copy carrying {@code entryId}; this selection is unchanged.
     */
    public StudioDeploymentSelection withCatalogEntryId(String entryId) {
        return new StudioDeploymentSelection(componentId, artifactCoordinates, capability, deploymentShape, requiredPorts, entryId);
    }
}
