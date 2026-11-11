package com.unfurl.fabric.compile;

import com.unfurl.fabric.compiler.ContractCompileException;
import com.unfurl.fabric.catalog.CatalogEntry;
import com.unfurl.fabric.catalog.CompanionArtifact;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

/**
 * Specification: a hosted component's pinned artifacts must keep clear of its host's shared API packages
 * (HLD-in-process-component-hosting §5). DCP's host refuses a pinned artifact that contains a class or resource under a
 * shared prefix ({@code ARTIFACT_SHADOWS_SHARED_API}), so Fabric refuses the same jar at compile time, as
 * {@code COMPANION_SHADOWS_SHARED_API}, naming the artifact and the entry. Base and versioned entries are both checked,
 * like the host does. Reading is metadata access only.
 */
final class HostedArtifactCheck {
    static final String CODE = "COMPANION_SHADOWS_SHARED_API";
    private static final String VERSIONS = "META-INF/versions/";

    private HostedArtifactCheck() { }

    /**
     * Checks the claim jar and every companion of a hosted entry.
     *
     * @throws ContractCompileException {@code COMPANION_SHADOWS_SHARED_API} on the first shadowing entry
     */
    static void requireClearOfSharedApi(CatalogEntry entry, HostedComponents.Placement placement) {
        List<Path> jars = new ArrayList<>();
        List<String> names = new ArrayList<>();
        if (entry.localPath() != null) {
            jars.add(entry.localPath());
            names.add(entry.artifact().coordinates());
        }
        for (CompanionArtifact companion : entry.companions()) {
            jars.add(companion.path());
            names.add(companion.coordinates());
        }
        for (int i = 0; i < jars.size(); i++) {
            String shadow = firstShadow(jars.get(i), placement.sharedApiPrefixes());
            if (shadow != null) {
                throw new ContractCompileException(CODE + ": " + names.get(i) + " (pinned for " + entry.artifact().coordinates()
                        + ") contains " + shadow + ", which lies in a package its host shares");
            }
        }
    }

    /** Returns the first entry under a shared prefix, base or versioned, or null. */
    private static String firstShadow(Path jar, List<String> prefixes) {
        try (JarFile file = new JarFile(jar.toFile(), false)) {
            for (Enumeration<JarEntry> entries = file.entries(); entries.hasMoreElements(); ) {
                String name = entries.nextElement().getName();
                String logical = name;
                if (name.startsWith(VERSIONS)) {
                    int slash = name.indexOf('/', VERSIONS.length());
                    if (slash < 0) continue;
                    logical = name.substring(slash + 1);
                }
                String dotted = logical.replace('/', '.');
                for (String prefix : prefixes) {
                    if (dotted.startsWith(prefix)) return name;
                }
            }
            return null;
        } catch (IOException unreadable) {
            throw new ContractCompileException("the pinned artifact " + jar.getFileName() + " cannot be read: " + unreadable.getMessage());
        }
    }
}
