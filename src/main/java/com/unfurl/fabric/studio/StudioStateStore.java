package com.unfurl.fabric.studio;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Repository adapter: persists the lightweight Studio read models used by the
 * local JDK HTTP server.
 *
 * <p>Pattern: persistence adapter. Production deployments can replace this
 * JSON-backed adapter with a database-backed implementation without changing
 * the application service records. Invariants: state is normalized on every
 * read/write, and all tenant-scoped maps remain keyed by tenant id.
 */
public final class StudioStateStore {
    private final Path path;
    private final ObjectMapper mapper;

    public StudioStateStore(Path path) {
        if (path == null) {
            throw new IllegalArgumentException("path is required");
        }
        this.path = path;
        this.mapper = StudioJson.mapper();
    }

    public static Path defaultPath() {
        String configured = System.getProperty("unfurl.studio.state.path");
        if (configured == null || configured.isBlank()) {
            configured = System.getenv("UNFURL_STUDIO_STATE_PATH");
        }
        if (configured != null && !configured.isBlank()) {
            return Path.of(configured);
        }
        return Path.of(System.getProperty("user.home"), ".unfurl", "studio-state.json");
    }

    public synchronized State load() {
        if (!Files.exists(path)) {
            return State.empty();
        }
        try {
            return mapper.readValue(path.toFile(), State.class).normalized();
        } catch (IOException ex) {
            throw new IllegalStateException("unable to read Studio state from " + path, ex);
        }
    }

    public synchronized void save(State state) {
        try {
            Path parent = path.toAbsolutePath().getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            mapper.writerWithDefaultPrettyPrinter().writeValue(path.toFile(), state.normalized());
        } catch (IOException ex) {
            throw new IllegalStateException("unable to write Studio state to " + path, ex);
        }
    }

    public Path path() {
        return path;
    }

    public record State(
            Map<String, List<StudioVisualCatalogEntry>> entriesByTenant,
            Map<String, Map<String, StudioAssemblySummary>> assembliesByTenant,
            Map<String, Map<String, StudioLayoutState>> layoutsByTenant,
            Map<String, Map<String, StudioDraftSession>> sessionsByTenant,
            Map<String, Map<String, StudioFileRecord>> filesByTenant,
            Map<String, List<StudioSessionFileLink>> sessionFileLinksByTenant,
            Map<String, Map<String, StudioCatalogSnapshot>> catalogSnapshotsByTenant
    ) {
        /**
         * Factory: builds an empty normalized state for first-run Studio stores.
         */
        public static State empty() {
            return new State(Map.of(), Map.of(), Map.of(), Map.of(), Map.of(), Map.of(), Map.of());
        }

        /**
         * Normalizer: copies nullable persisted maps into immutable, tenant-keyed
         * structures so service code can safely hydrate concurrent working maps.
         */
        public State normalized() {
            return new State(
                    entriesByTenant == null ? Map.of() : copyEntries(entriesByTenant),
                    assembliesByTenant == null ? Map.of() : copyAssemblies(assembliesByTenant),
                    layoutsByTenant == null ? Map.of() : copyLayouts(layoutsByTenant),
                    sessionsByTenant == null ? Map.of() : copySessions(sessionsByTenant),
                    filesByTenant == null ? Map.of() : copyFiles(filesByTenant),
                    sessionFileLinksByTenant == null ? Map.of() : copyLinks(sessionFileLinksByTenant),
                    catalogSnapshotsByTenant == null ? Map.of() : copySnapshots(catalogSnapshotsByTenant));
        }

        private static Map<String, List<StudioVisualCatalogEntry>> copyEntries(
                Map<String, List<StudioVisualCatalogEntry>> source
        ) {
            Map<String, List<StudioVisualCatalogEntry>> copy = new HashMap<>();
            source.forEach((tenant, entries) -> copy.put(tenant, entries == null ? List.of() : List.copyOf(entries)));
            return copy;
        }

        private static Map<String, Map<String, StudioAssemblySummary>> copyAssemblies(
                Map<String, Map<String, StudioAssemblySummary>> source
        ) {
            Map<String, Map<String, StudioAssemblySummary>> copy = new HashMap<>();
            source.forEach((tenant, assemblies) -> copy.put(tenant, assemblies == null ? Map.of() : Map.copyOf(assemblies)));
            return copy;
        }

        private static Map<String, Map<String, StudioLayoutState>> copyLayouts(
                Map<String, Map<String, StudioLayoutState>> source
        ) {
            Map<String, Map<String, StudioLayoutState>> copy = new HashMap<>();
            source.forEach((tenant, layouts) -> copy.put(tenant, layouts == null ? Map.of() : Map.copyOf(layouts)));
            return copy;
        }

        private static Map<String, Map<String, StudioDraftSession>> copySessions(
                Map<String, Map<String, StudioDraftSession>> source
        ) {
            Map<String, Map<String, StudioDraftSession>> copy = new HashMap<>();
            source.forEach((tenant, sessions) -> copy.put(tenant, sessions == null ? Map.of() : Map.copyOf(sessions)));
            return copy;
        }

        private static Map<String, Map<String, StudioFileRecord>> copyFiles(
                Map<String, Map<String, StudioFileRecord>> source
        ) {
            Map<String, Map<String, StudioFileRecord>> copy = new HashMap<>();
            source.forEach((tenant, files) -> copy.put(tenant, files == null ? Map.of() : Map.copyOf(files)));
            return copy;
        }

        private static Map<String, List<StudioSessionFileLink>> copyLinks(
                Map<String, List<StudioSessionFileLink>> source
        ) {
            Map<String, List<StudioSessionFileLink>> copy = new HashMap<>();
            source.forEach((tenant, links) -> copy.put(tenant, links == null ? List.of() : List.copyOf(links)));
            return copy;
        }

        private static Map<String, Map<String, StudioCatalogSnapshot>> copySnapshots(
                Map<String, Map<String, StudioCatalogSnapshot>> source
        ) {
            Map<String, Map<String, StudioCatalogSnapshot>> copy = new HashMap<>();
            source.forEach((tenant, snapshots) -> copy.put(tenant, snapshots == null ? Map.of() : Map.copyOf(snapshots)));
            return copy;
        }
    }
}
