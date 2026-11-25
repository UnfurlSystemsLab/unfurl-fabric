package com.unfurl.fabric.studio;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * The bytes a {@link StudioCompileRecord} names: compiled envelopes, substrate profiles and signed envelopes, kept by
 * SHA-256 per tenant so a past compile can be diffed and verified after later compiles and after a restart
 * (LLD-fabric-studio-api, <i>Durable compiled contracts</i>).
 *
 * <p>Pattern: <b>Repository</b>, content-addressed and partitioned by tenant.
 * <ul>
 *   <li><b>Content addressing.</b> Content is named by its own hash, so it is never overwritten or deleted: putting the
 *       same bytes twice stores them once, and different bytes always get a different name. A read recomputes the hash
 *       and refuses bytes that no longer match it, so a damaged or edited file is reported as unavailable instead of
 *       being used.</li>
 *   <li><b>Tenant partition.</b> A tenant reads only what was stored for it. Records travel inside assembly snapshots,
 *       so without the partition an imported record could name another tenant's contract by its hash. The tenant's
 *       folder is named by the SHA-256 of its id, as in {@code StudioCompanionStore}, so no tenant id can name a path
 *       outside the store.</li>
 *   <li><b>File-backed</b> in {@code studio-compiled/} beside the Studio state file. A write goes to a temporary file in
 *       the same folder and is then moved into place, so a reader never sees half a file. <b>In memory</b> when Studio
 *       keeps no state file.</li>
 * </ul>
 */
public final class StudioCompiledContractStore {
    /** The folder name beside the Studio state file. */
    public static final String FOLDER = "studio-compiled";
    private static final Pattern SHA256 = Pattern.compile("[0-9a-f]{64}");

    private final Path root;
    private final Map<String, Map<String, byte[]>> memory;

    private StudioCompiledContractStore(Path root) {
        this.root = root;
        this.memory = root == null ? new ConcurrentHashMap<>() : null;
    }

    /**
     * Factory: the store for a Studio state file ({@code <state dir>/studio-compiled}), or one in memory when Studio
     * keeps no state.
     *
     * @param state the Studio state store, or null.
     * @return the store.
     */
    public static StudioCompiledContractStore forState(StudioStateStore state) {
        if (state == null) {
            return inMemory();
        }
        Path parent = state.path().toAbsolutePath().getParent();
        return inFolder((parent == null ? Path.of(".") : parent).resolve(FOLDER));
    }

    /**
     * Factory: a store kept in a folder, created on first write.
     *
     * @param root the folder holding one sub-folder per tenant.
     * @return the store.
     */
    public static StudioCompiledContractStore inFolder(Path root) {
        if (root == null) {
            throw new IllegalArgumentException("root is required");
        }
        return new StudioCompiledContractStore(root.toAbsolutePath().normalize());
    }

    /**
     * Factory: a store kept in memory, for a Studio without a state file.
     *
     * @return the store.
     */
    public static StudioCompiledContractStore inMemory() {
        return new StudioCompiledContractStore(null);
    }

    /**
     * Stores bytes for a tenant under their SHA-256 and returns it. Bytes already stored are left as they are.
     *
     * @param tenant the normalized tenant id.
     * @param bytes  the content.
     * @return its lower-case hex SHA-256.
     * @throws UncheckedIOException when the folder cannot be written.
     */
    public String put(String tenant, byte[] bytes) {
        String tenantKey = tenantKey(tenant);
        byte[] content = bytes == null ? new byte[0] : bytes.clone();
        String sha256 = sha256(content);
        if (memory != null) {
            memory.computeIfAbsent(tenantKey, ignored -> new ConcurrentHashMap<>()).putIfAbsent(sha256, content);
            return sha256;
        }
        Path folder = root.resolve(tenantKey);
        Path target = folder.resolve(sha256);
        if (Files.isRegularFile(target)) {
            return sha256;
        }
        try {
            Files.createDirectories(folder);
            Path temporary = Files.createTempFile(folder, sha256 + ".", ".tmp");
            try {
                Files.write(temporary, content);
                moveIntoPlace(temporary, target);
            } finally {
                Files.deleteIfExists(temporary);
            }
        } catch (IOException ex) {
            throw new UncheckedIOException("unable to store compiled content " + sha256, ex);
        }
        return sha256;
    }

    /**
     * Reads the bytes stored for a tenant under a hash, only when they still hash to it.
     *
     * @param tenant the normalized tenant id.
     * @param sha256 the content's hash.
     * @return the bytes, or empty when nothing is stored for the tenant under the hash, the hash is malformed, or the
     *         stored bytes no longer match it.
     */
    public Optional<byte[]> get(String tenant, String sha256) {
        if (sha256 == null || !SHA256.matcher(sha256).matches()) {
            return Optional.empty();
        }
        String tenantKey = tenantKey(tenant);
        byte[] content;
        if (memory != null) {
            content = memory.getOrDefault(tenantKey, Map.of()).get(sha256);
            if (content == null) {
                return Optional.empty();
            }
            content = content.clone();
        } else {
            Path file = root.resolve(tenantKey).resolve(sha256);
            if (!Files.isRegularFile(file)) {
                return Optional.empty();
            }
            try {
                content = Files.readAllBytes(file);
            } catch (IOException ex) {
                return Optional.empty();
            }
        }
        // Content addressing is the integrity check: bytes that no longer hash to their name are never used.
        return sha256.equals(sha256(content)) ? Optional.of(content) : Optional.empty();
    }

    /** The folder of a file-backed store, or empty for one in memory. */
    public Optional<Path> root() {
        return Optional.ofNullable(root);
    }

    /** The tenant's folder name: the SHA-256 of its id, so no id can name a path outside the store. */
    private static String tenantKey(String tenant) {
        if (tenant == null || tenant.isBlank()) {
            throw new IllegalArgumentException("a tenant id is required for the compiled-contract store");
        }
        return sha256(tenant.getBytes(StandardCharsets.UTF_8));
    }

    /** Moves a written temporary file to its final name atomically, falling back to a plain move where unsupported. */
    private static void moveIntoPlace(Path temporary, Path target) throws IOException {
        try {
            Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE);
        } catch (FileAlreadyExistsException ex) {
            // Another writer stored the same content first; content addressing makes the two identical.
        } catch (AtomicMoveNotSupportedException ex) {
            try {
                Files.move(temporary, target);
            } catch (FileAlreadyExistsException alreadyStored) {
                // Same as above: identical content is already in place.
            }
        }
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is unavailable", ex);
        }
    }
}
