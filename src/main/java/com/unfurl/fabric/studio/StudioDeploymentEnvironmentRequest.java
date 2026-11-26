package com.unfurl.fabric.studio;

/**
 * The body of {@code PUT .../deployment-environments/{name}} (LLD-fabric-studio-api, <i>Deployment Environments</i>).
 *
 * <p>Pattern: immutable request <b>DTO</b>. {@code expectedRevision} is optimistic concurrency: 0 creates an environment
 * that does not exist yet, and any other value must equal the stored revision, so two editors never overwrite each other.
 *
 * @param expectedRevision the revision the editor last read, or 0 to create.
 * @param target           where the environment runs.
 * @param bindings         the runtime-binding inputs.
 */
public record StudioDeploymentEnvironmentRequest(long expectedRevision, StudioDeploymentEnvironment.Target target,
                                                 StudioDeploymentEnvironment.Bindings bindings) {
}
