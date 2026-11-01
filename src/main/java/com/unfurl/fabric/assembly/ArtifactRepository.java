package com.unfurl.fabric.assembly;

import java.io.IOException;
import java.util.Map;

/** Repository port: reads indexed payloads and commits complete immutable bundles; implementations never replace a key. */
public interface ArtifactRepository {
    /** Read one payload under a bundle key; absence throws NoSuchFileException and unsafe paths fail explicitly. */
    byte[] read(String key, String path) throws IOException;

    /** Commit a complete bundle; an identical retry succeeds and any conflicting bytes fail. */
    void put(String key, Map<String, byte[]> files) throws IOException;
}
