package com.unfurl.fabric.studio;

import java.time.Instant;

/**
 * Which compile a compile-derived value came from: the record's revision, contract hash and time
 * (LLD-fabric-studio-api, <i>Compile records and freshness</i>, Provenance). Studio labels every such value with it.
 *
 * <p>Pattern: immutable <b>value object</b> projected from a {@link StudioCompileRecord}.
 *
 * @param sourceRevision the session revision that was compiled.
 * @param contractHash   the compiled contract's canonical hash.
 * @param compiledAt     when the compile finished.
 */
public record StudioCompileProvenance(long sourceRevision, String contractHash, Instant compiledAt) {
    /**
     * Factory: the provenance of a record.
     *
     * @param record the compile record.
     * @return its provenance.
     */
    public static StudioCompileProvenance of(StudioCompileRecord record) {
        return new StudioCompileProvenance(record.revision(), record.contractHash(), record.compiledAt());
    }
}
