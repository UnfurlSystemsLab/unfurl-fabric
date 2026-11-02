package com.unfurl.fabric.assembly;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.util.Arrays;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/** Filesystem Adapter: promotes immutable staged bundles under a host-owned root with exclusive writer locking. */
public final class FileArtifactRepository implements ArtifactRepository {
    private final Path root;

    /** Bind to an absolute normalized repository root; existing symlink ancestors are rejected on every operation. */
    public FileArtifactRepository(Path root) {
        this.root = root.toAbsolutePath().normalize();
    }

    /** Read a regular non-link payload while keeping every path component inside the configured root. */
    @Override public byte[] read(String key, String path) throws IOException {
        Path target = resolve(key + "/" + path);
        if (!Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS)) throw new NoSuchFileException(target.toString());
        return Files.readAllBytes(target);
    }

    /** Commit atomically, serialized across processes; partial stages never appear as published keys. */
    @Override public void put(String key, Map<String, byte[]> files) throws IOException {
        if (files.isEmpty()) throw new IOException("empty artifact bundle");
        Path target = resolve(key);
        Files.createDirectories(root);
        Path lockPath = resolve(".repository.lock");
        try (FileChannel channel = FileChannel.open(lockPath, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
             var lock = channel.lock()) {
            if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
                try (var paths = Files.walk(target)) {
                    Set<String> existing = paths.filter(p -> !Files.isDirectory(p, LinkOption.NOFOLLOW_LINKS))
                            .map(p -> target.relativize(p).toString().replace('\\', '/')).collect(Collectors.toSet());
                    if (!existing.equals(files.keySet())) throw new IOException("immutable bundle conflict: " + key);
                }
                for (var entry : files.entrySet()) {
                    if (!Arrays.equals(read(key, entry.getKey()), entry.getValue()))
                        throw new IOException("immutable bundle conflict: " + key);
                }
                return;
            }
            Path stage = Files.createTempDirectory(root, ".stage-");
            try {
                for (var entry : files.entrySet()) {
                    Path file = stage.resolve(relative(entry.getKey())).normalize();
                    Files.createDirectories(file.getParent());
                    Files.write(file, entry.getValue(), StandardOpenOption.CREATE_NEW);
                }
                Files.createDirectories(target.getParent());
                resolve(key); // Recheck existing ancestors after directory creation.
                Files.move(stage, target, StandardCopyOption.ATOMIC_MOVE);
            } finally {
                if (Files.exists(stage)) {
                    try (var paths = Files.walk(stage)) {
                        for (Path path : paths.sorted(java.util.Comparator.reverseOrder()).toList()) Files.delete(path);
                    }
                }
            }
        }
    }

    /** Containment guard: reject traversal and links, including links above the configured repository root. */
    private Path resolve(String path) throws IOException {
        Path target = root.resolve(relative(path));
        for (Path cursor = target; cursor != null; cursor = cursor.getParent()) {
            if (Files.isSymbolicLink(cursor)) throw new IOException("repository links are forbidden: " + cursor);
        }
        return target;
    }

    /** Portable relative-path parser: disallow platform-specific roots, empty segments and traversal before normalization. */
    private static Path relative(String value) throws IOException {
        if (value == null || value.isBlank() || value.contains("\\") || value.contains(":"))
            throw new IOException("unsafe artifact path: " + value);
        for (String part : value.split("/", -1)) {
            if (part.isBlank() || part.equals(".") || part.equals("..")) throw new IOException("unsafe artifact path: " + value);
        }
        Path path = Path.of(value);
        if (path.isAbsolute()) throw new IOException("absolute artifact path is forbidden");
        return path;
    }
}
