package com.unfurl.fabric.studio;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Stream;

/**
 * Fingerprint of the files an emit-plan preview reads from outside Studio state (LLD-fabric-studio-api,
 * <i>StudioEmitPlanPreview</i>, Cache): the trust-key folder, Studio's signing key files and the operator paths an
 * environment names. A cached preview is reused only while this fingerprint is unchanged, so editing, adding or removing
 * one of those files, or repairing a missing one, makes the next preview render again.
 *
 * <p>Pattern: <b>Value Object</b> factory (a pure function of the file system's metadata). It reads metadata only, each
 * file's relative path, size and modification time, walking folders; it never opens a file, so a secrets folder's content
 * is never read. A missing path is recorded as missing, so its later appearance changes the fingerprint.
 */
final class StudioPathFingerprint {
    private StudioPathFingerprint() {
    }

    /**
     * The fingerprint of labelled paths.
     *
     * @param paths each path by a label naming what it is (for example {@code option:flowWorkflowsPath}); a null path is
     *              recorded as not configured.
     * @return lower-case hex SHA-256 over the sorted labels and their files' metadata.
     */
    static String of(Map<String, Path> paths) {
        List<String> lines = new ArrayList<>();
        new TreeMap<>(paths).forEach((label, path) -> {
            if (path == null) {
                lines.add(label + " not-configured");
            } else if (!Files.exists(path)) {
                lines.add(label + " missing " + path.toAbsolutePath().normalize());
            } else {
                lines.add(label + " at " + path.toAbsolutePath().normalize());
                lines.addAll(files(label, path));
            }
        });
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (String line : lines) {
                digest.update(line.getBytes(StandardCharsets.UTF_8));
                digest.update((byte) '\n');
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    /** One line per file under a path (the path itself when it is a file): relative path, size, modification time. */
    private static List<String> files(String label, Path root) {
        try (Stream<Path> walk = Files.walk(root)) {
            List<String> lines = new ArrayList<>();
            for (Path file : walk.sorted().toList()) {
                BasicFileAttributes attributes = Files.readAttributes(file, BasicFileAttributes.class);
                if (!attributes.isRegularFile()) continue;
                String relative = root.equals(file) ? file.getFileName().toString()
                        : root.relativize(file).toString().replace('\\', '/');
                lines.add(label + " file " + relative + " " + attributes.size() + " " + attributes.lastModifiedTime().toMillis());
            }
            return lines;
        } catch (IOException unreadable) {
            throw new UncheckedIOException("unable to read the metadata of " + root, unreadable);
        }
    }
}
