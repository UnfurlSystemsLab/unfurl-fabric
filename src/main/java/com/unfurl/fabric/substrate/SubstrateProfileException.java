package com.unfurl.fabric.substrate;

/**
 * Sealed exception taxonomy for substrate-profile derivation failures.
 *
 * <p>Pattern: <b>sealed class hierarchy</b> as a closed, typed error taxonomy. The permitted subtypes
 * enumerate every way profile derivation can fail; the {@link Reason} enum lets callers branch on the
 * cause without {@code instanceof}. Sealing guarantees the taxonomy is exhaustive and cannot be extended
 * outside this file.
 */
public sealed class SubstrateProfileException extends RuntimeException
        permits SubstrateProfileException.NotSubstrateDependency,
                SubstrateProfileException.MalformedDependency,
                SubstrateProfileException.ConflictingVersions {
    /** Machine-readable cause, so callers branch on the reason rather than the message text. */
    private final Reason reason;

    /**
     * Base constructor used by the permitted subtypes.
     *
     * @param reason  the machine-readable failure category.
     * @param message the human-readable detail.
     */
    protected SubstrateProfileException(Reason reason, String message) {
        super(message);
        this.reason = reason;
    }

    /**
     * @return the machine-readable failure category for this exception.
     */
    public Reason reason() {
        return reason;
    }

    /** The closed set of substrate-profile failure categories. */
    public enum Reason {
        /** A dependency URI was not marked {@code substrate=true}. */
        NOT_SUBSTRATE_DEPENDENCY,
        /** A substrate dependency URI was syntactically invalid. */
        MALFORMED_DEPENDENCY,
        /** Two declarations of the same port carried incompatible version ranges. */
        CONFLICTING_VERSIONS
    }

    /** Raised when a dependency URI lacks the {@code substrate=true} marker. */
    public static final class NotSubstrateDependency extends SubstrateProfileException {
        /**
         * @param dependency the offending raw dependency string.
         */
        public NotSubstrateDependency(String dependency) {
            super(Reason.NOT_SUBSTRATE_DEPENDENCY,
                    "dependency is not marked substrate=true: " + dependency);
        }
    }

    /** Raised when a substrate dependency URI is structurally invalid. */
    public static final class MalformedDependency extends SubstrateProfileException {
        /**
         * @param dependency the offending raw dependency string.
         * @param detail     what specifically was malformed.
         */
        public MalformedDependency(String dependency, String detail) {
            super(Reason.MALFORMED_DEPENDENCY,
                    "malformed substrate dependency " + dependency + ": " + detail);
        }
    }

    /** Raised when the same port is required at two incompatible version ranges across entries. */
    public static final class ConflictingVersions extends SubstrateProfileException {
        /**
         * @param port  the substrate port whose ranges conflict.
         * @param left  one declared version range.
         * @param right the other declared version range.
         */
        public ConflictingVersions(String port, String left, String right) {
            super(Reason.CONFLICTING_VERSIONS,
                    "conflicting substrate version ranges for " + port + ": " + left + " vs " + right);
        }
    }
}
