package com.unfurl.fabric.compiler;

import com.unfurl.dcp.claim.InterfaceKind;
import com.unfurl.fabric.catalog.ArtifactDescriptor;
import com.unfurl.substrate.api.BindingMode;

public record SelectionRecord(
        ArtifactDescriptor artifact,
        String claimHash,
        BindingMode bindingMode,
        InterfaceKind chosenInterfaceKind
) {
    public SelectionRecord {
        if (artifact == null) {
            throw new IllegalArgumentException("artifact is required");
        }
        if (artifact.sha256() == null || !artifact.sha256().matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("artifact sha256 must be lowercase SHA-256 hex");
        }
        if (claimHash == null || !claimHash.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("claimHash must be lowercase SHA-256 hex");
        }
        if (bindingMode == null) {
            throw new IllegalArgumentException("bindingMode is required");
        }
        if (chosenInterfaceKind == null) {
            chosenInterfaceKind = InterfaceKind.IN_PROCESS;
        }
    }
}
