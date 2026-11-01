package com.unfurl.fabric.assembly;

import java.util.Map;

/** Value Object: signed storage association between publisher identity, immutable payload hashes, and optional deployment scope. */
public record AssemblyIndex(int formatVersion, String kind, String uri, String claimVersion, String releaseVersion,
                            String family, String profile, String tenant, String environment, String revision,
                            String releaseManifestHash, Map<String, String> files) {
    /** Freeze the payload inventory; semantic validation belongs to the storage service before admission. */
    public AssemblyIndex {
        files = files == null ? Map.of() : Map.copyOf(files);
    }
}
