package com.unfurl.fabric.studio;

import java.time.Instant;

/**
 * One successful compile of a Studio draft session: what was compiled, with which deployment policy, and where its
 * envelopes are kept (LLD-fabric-studio-api, <i>Compile records and freshness</i>).
 *
 * <p>Pattern: immutable <b>value object</b>, appended to {@link StudioDraftSession#compileRecords()} once per
 * {@code COMPILED} result and never edited, like the session's intent log. Every compile-derived value Studio shows is
 * traced to one record, and {@link StudioCompileFreshness} decides whether that record still describes the draft.
 *
 * @param revision               the session revision that was compiled.
 * @param deploymentPolicyHash   the canonical hash of the effective deployment policy the compile resolved with
 *                               ({@link StudioCompileFreshness#policyHash}).
 * @param contractHash           the compiled contract's canonical hash: SHA-256 of
 *                               {@code CompiledContractCodec.canonicalBytes}, the bytes signing signs.
 * @param compiledEnvelopeSha256 SHA-256 of the stored compiled envelope ({@link StudioCompiledContractStore}).
 * @param substrateProfileSha256 SHA-256 of the stored substrate profile; the envelope carries only its hash.
 * @param signedEnvelopeSha256   SHA-256 of the stored signed envelope, or null when the compile was not signed (not
 *                               requested, or requested and failed).
 * @param bindingsHash           hash of runtime bindings generated from this contract, or null (set from P2b).
 * @param compiledAt             when the compile finished.
 */
public record StudioCompileRecord(
        long revision,
        String deploymentPolicyHash,
        String contractHash,
        String compiledEnvelopeSha256,
        String substrateProfileSha256,
        String signedEnvelopeSha256,
        String bindingsHash,
        Instant compiledAt
) {
    /** Compact constructor: requires the hashes every record has, and blanks optional ones to null. */
    public StudioCompileRecord {
        requireHash(deploymentPolicyHash, "deploymentPolicyHash");
        requireHash(contractHash, "contractHash");
        requireHash(compiledEnvelopeSha256, "compiledEnvelopeSha256");
        requireHash(substrateProfileSha256, "substrateProfileSha256");
        signedEnvelopeSha256 = signedEnvelopeSha256 == null || signedEnvelopeSha256.isBlank() ? null : signedEnvelopeSha256;
        bindingsHash = bindingsHash == null || bindingsHash.isBlank() ? null : bindingsHash;
        if (compiledAt == null) {
            throw new IllegalArgumentException("compiledAt is required");
        }
    }

    /** Whether the compile produced a signed envelope. */
    public boolean signed() {
        return signedEnvelopeSha256 != null;
    }

    private static void requireHash(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " is required");
        }
    }
}
