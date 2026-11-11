package com.unfurl.fabric.catalog;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Properties;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Reader of a companion jar's identity (HLD-in-process-component-hosting §3): every
 * {@code META-INF/maven/<group>/<artifact>/pom.properties} in the jar's bytes. A companion candidate has exactly one,
 * and it is complete ({@code groupId}, {@code artifactId} and {@code version}). Every such file counts, complete or not,
 * so a shaded jar can never pass as single-artifact by having all but one of its files incomplete. The CLI scan
 * ({@link CompanionIndex}) and Studio admission both read coordinates here, from the same bytes they hash, so the two
 * can never disagree about which artifact a jar is. Reading is metadata access only; no code runs.
 */
public final class CompanionCoordinates {
    /** A hosted entry names a companion the catalog does not have. */
    public static final String MISSING = "CATALOG_COMPANION_MISSING";
    /** Two jars carry one companion's coordinates with different bytes. */
    public static final String CONFLICT = "CATALOG_COMPANION_CONFLICT";
    private static final String POM_PROPERTIES = "/pom.properties";
    private static final String MAVEN_DIR = "META-INF/maven/";

    private CompanionCoordinates() { }

    /**
     * Value Object: what a jar's Maven metadata says about its identity.
     *
     * @param coordinates the {@code group:artifact:version} of each complete {@code pom.properties}, in entry order
     * @param entries     how many {@code pom.properties} sit at the Maven location, complete or not
     */
    public record Identity(List<String> coordinates, int entries) {
        /** Copies the coordinates. */
        public Identity {
            coordinates = List.copyOf(coordinates);
        }

        /** Returns the jar's one coordinate set, when it has exactly one {@code pom.properties} and that one is complete. */
        public Optional<String> single() {
            return entries == 1 && coordinates.size() == 1 ? Optional.of(coordinates.get(0)) : Optional.empty();
        }

        /** Why the jar is not a companion candidate; meaningful only when {@link #single()} is empty. */
        public String refusal() {
            if (entries != 1) {
                return "not a companion candidate: it has " + entries + " pom.properties entries, and a companion needs exactly one";
            }
            return "not a companion candidate: its pom.properties lacks groupId, artifactId or version";
        }
    }

    /**
     * Reads every {@code pom.properties} at the Maven location.
     *
     * @param jar the jar bytes
     * @return the complete coordinates and the count of all such files
     * @throws IOException when the bytes are not a readable zip
     */
    public static Identity read(byte[] jar) throws IOException {
        List<String> found = new ArrayList<>();
        int entries = 0;
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(jar))) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                String name = entry.getName();
                if (!name.startsWith(MAVEN_DIR) || !name.endsWith(POM_PROPERTIES) || name.split("/").length != 5) continue;
                entries++;
                Properties properties = new Properties();
                properties.load(new ByteArrayInputStream(zip.readAllBytes()));
                String group = properties.getProperty("groupId");
                String artifact = properties.getProperty("artifactId");
                String version = properties.getProperty("version");
                if (isPresent(group) && isPresent(artifact) && isPresent(version)) {
                    found.add(group.trim() + ":" + artifact.trim() + ":" + version.trim());
                }
            }
        }
        return new Identity(found, entries);
    }

    private static boolean isPresent(String value) {
        return value != null && !value.isBlank();
    }
}
