package com.unfurl.fabric.studio;

/**
 * Why a Studio read model has no value to show: a stable code plus a message naming the session, revision or input
 * involved (LLD-fabric-studio-api, <i>Views and Overlays</i>, response rules). Studio shows it as Fabric names it and
 * never substitutes a default.
 *
 * <p>Pattern: immutable <b>value object</b>.
 *
 * @param code    the stable code, for example {@code COMPILE_STALE} or {@code NOT_SIGNED}.
 * @param message what it means here, for example "compiled at revision 7; the draft is at revision 9".
 */
public record StudioAbsentReason(String code, String message) {
    /** The codes the P2a routes answer with. */
    public static final String COMPILE_REQUIRED = "COMPILE_REQUIRED";
    public static final String COMPILE_STALE = "COMPILE_STALE";
    public static final String COMPILE_RECORD_UNAVAILABLE = "COMPILE_RECORD_UNAVAILABLE";
    /** A record's stored bytes do not belong to it (another contract, profile or signed envelope). */
    public static final String COMPILE_RECORD_INCONSISTENT = "COMPILE_RECORD_INCONSISTENT";
    public static final String NOT_SIGNED = "NOT_SIGNED";
    public static final String TRUST_KEYS_UNAVAILABLE = "TRUST_KEYS_UNAVAILABLE";

    /** Compact constructor: requires a code and a message. */
    public StudioAbsentReason {
        if (code == null || code.isBlank()) {
            throw new IllegalArgumentException("code is required");
        }
        if (message == null || message.isBlank()) {
            throw new IllegalArgumentException("message is required");
        }
    }
}
