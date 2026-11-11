package com.unfurl.fabric.catalog;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Reader of a companion jar's identity (HLD-in-process-component-hosting §3): the {@code group:artifact:version} of
 * every {@code META-INF/maven/<group>/<artifact>/pom.properties} in the jar's bytes. A companion candidate has exactly
 * one. The CLI scan ({@link CompanionIndex}) and Studio admission both read coordinates here, from the same bytes they
 * hash, so the two can never disagree about which artifact a jar is. Reading is metadata access only; no code runs.
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
     * Reads the coordinates of every complete {@code pom.properties} at the Maven location.
     *
     * @param jar the jar bytes
     * @return the coordinates in entry order; a properties file missing a field is not counted
     * @throws IOException when the bytes are not a readable zip
     */
    public static List<String> read(byte[] jar) throws IOException {
        List<String> found = new ArrayList<>();
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(jar))) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                String name = entry.getName();
                if (!name.startsWith(MAVEN_DIR) || !name.endsWith(POM_PROPERTIES) || name.split("/").length != 5) continue;
                Properties properties = new Properties();
                properties.load(new ByteArrayInputStream(zip.readAllBytes()));
                String group = properties.getProperty("groupId");
                String artifact = properties.getProperty("artifactId");
                String version = properties.getProperty("version");
                if (group != null && artifact != null && version != null) found.add(group + ":" + artifact + ":" + version);
            }
        }
        return found;
    }

    /** The refusal for a jar that is not a companion candidate, given how many coordinates it carries. */
    public static String notACandidate(int count) {
        return "not a companion candidate: it has " + count + " pom.properties entries, and a companion needs exactly one";
    }
}
