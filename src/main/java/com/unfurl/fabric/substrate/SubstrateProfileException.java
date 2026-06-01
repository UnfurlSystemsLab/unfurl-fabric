package com.unfurl.fabric.substrate;

public sealed class SubstrateProfileException extends RuntimeException
        permits SubstrateProfileException.NotSubstrateDependency,
                SubstrateProfileException.MalformedDependency,
                SubstrateProfileException.ConflictingVersions {
    private final Reason reason;

    protected SubstrateProfileException(Reason reason, String message) {
        super(message);
        this.reason = reason;
    }

    public Reason reason() {
        return reason;
    }

    public enum Reason {
        NOT_SUBSTRATE_DEPENDENCY,
        MALFORMED_DEPENDENCY,
        CONFLICTING_VERSIONS
    }

    public static final class NotSubstrateDependency extends SubstrateProfileException {
        public NotSubstrateDependency(String dependency) {
            super(Reason.NOT_SUBSTRATE_DEPENDENCY,
                    "dependency is not marked substrate=true: " + dependency);
        }
    }

    public static final class MalformedDependency extends SubstrateProfileException {
        public MalformedDependency(String dependency, String detail) {
            super(Reason.MALFORMED_DEPENDENCY,
                    "malformed substrate dependency " + dependency + ": " + detail);
        }
    }

    public static final class ConflictingVersions extends SubstrateProfileException {
        public ConflictingVersions(String port, String left, String right) {
            super(Reason.CONFLICTING_VERSIONS,
                    "conflicting substrate version ranges for " + port + ": " + left + " vs " + right);
        }
    }
}
