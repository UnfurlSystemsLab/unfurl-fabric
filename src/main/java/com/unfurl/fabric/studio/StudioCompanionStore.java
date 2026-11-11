package com.unfurl.fabric.studio;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Repository: the bytes of companion jars admitted to Studio (HLD-in-process-component-hosting §3), tenant-scoped and
 * content-addressed at {@code <root>/<sha256(tenant id)>/<sha256>.jar}; the tenant folder is named by a hash so any tenant
 * id is a safe, distinct folder name. Writes are write-once: storing bytes whose file already
 * exists is a no-op, and a file is written to a temporary name and moved into place, so a stored path never holds
 * partial or different bytes. Compile reads companions from here, so the shared-API check sees the admitted bytes.
 *
 * <p>The root is next to the persisted Studio state; an in-memory Studio (no state store) uses a temporary directory
 * for the process lifetime, matching the rest of its state.
 */
final class StudioCompanionStore {
    private static final Pattern SHA256 = Pattern.compile("[0-9a-f]{64}");
    private final Path root;

    /** @param root the directory holding one folder per tenant */
    StudioCompanionStore(Path root) {
        this.root = root.toAbsolutePath().normalize();
    }

    /**
     * Factory: the store for a Studio state file ({@code <state dir>/studio-companions}), or a temporary one when
     * Studio keeps no state.
     */
    static StudioCompanionStore forState(StudioStateStore state) {
        try {
            if (state == null) {
                return new StudioCompanionStore(Files.createTempDirectory("studio-companions-"));
            }
            Path parent = state.path().toAbsolutePath().getParent();
            return new StudioCompanionStore((parent == null ? Path.of(".") : parent).resolve("studio-companions"));
        } catch (IOException ex) {
            throw new UncheckedIOException("unable to create the Studio companion store", ex);
        }
    }

    /**
     * Stores the bytes and returns their path.
     *
     * @param tenant the normalized tenant id
     * @param bytes  the companion jar
     * @return the stored file, named by the bytes' SHA-256
     */
    Path store(String tenant, byte[] bytes) {
        Path target = path(tenant, sha256(bytes));
        if (Files.exists(target)) {
            return target;
        }
        try {
            Files.createDirectories(target.getParent());
            Path temp = Files.createTempFile(target.getParent(), "upload-", ".tmp");
            try {
                Files.write(temp, bytes);
                Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE);
            } catch (FileAlreadyExistsException concurrent) {
                // Another upload of the same bytes won the move; the stored file already holds them.
            } finally {
                Files.deleteIfExists(temp);
            }
            return target;
        } catch (IOException ex) {
            throw new UncheckedIOException("unable to store companion " + target.getFileName(), ex);
        }
    }

    /**
     * Returns where the bytes with this hash are stored for the tenant. The hash is validated and the tenant folder is a
     * hash, so neither can name a path outside the store.
     */
    Path path(String tenant, String sha256) {
        if (tenant == null || tenant.isBlank()) {
            throw new IllegalArgumentException("a tenant id is required for the companion store");
        }
        if (sha256 == null || !SHA256.matcher(sha256).matches()) {
            throw new IllegalArgumentException("companion hash must be 64 lower-case hex characters");
        }
        return root.resolve(sha256(tenant.getBytes(java.nio.charset.StandardCharsets.UTF_8))).resolve(sha256 + ".jar");
    }

    /** Lowercase hex SHA-256. */
    static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)).toLowerCase(Locale.ROOT);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }
}
