package com.unfurl.fabric.studio;

import com.unfurl.fabric.compiler.CompiledContract;
import com.unfurl.fabric.compiler.CompiledContractCodec;
import com.unfurl.fabric.signing.SignedFabricContract;
import com.unfurl.fabric.signing.SignedFabricContractCodec;
import com.unfurl.substrate.api.SubstrateProfile;
import com.unfurl.substrate.api.SubstrateProfileCodec;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.function.Function;

/**
 * Reads what a {@link StudioCompileRecord} names back out of the {@link StudioCompiledContractStore} and proves that it
 * belongs to the record before anything is derived from it (LLD-fabric-studio-api, <i>Durable compiled contracts</i>,
 * <i>A record is checked against its bytes</i>).
 *
 * <p>Pattern: <b>Adapter</b> from stored bytes to the typed contracts {@code contract-diff} and {@code verify} work on,
 * with a <b>Guard</b> over the result. A record only names hashes; the reader checks them:
 * <ul>
 *   <li>the compiled envelope's canonical hash equals the record's {@code contractHash};</li>
 *   <li>the stored profile's recomputed hash equals the envelope's {@code substrateProfileHash};</li>
 *   <li>the signed envelope's {@code canonicalHash}, and the canonical hash of the contract it carries, equal the
 *       record's {@code contractHash}.</li>
 * </ul>
 * Bytes that are missing or do not parse are {@link Unavailable}; bytes that do not belong to the record are
 * {@link Inconsistent}. Either way nothing is diffed or verified, so a result always describes the contract its
 * provenance names.
 */
final class StudioCompiledContractReader {
    private final StudioCompiledContractStore store;
    private final CompiledContractCodec compiledCodec = new CompiledContractCodec();
    private final SubstrateProfileCodec profileCodec = new SubstrateProfileCodec();

    /** @param store the store compile wrote to. */
    StudioCompiledContractReader(StudioCompiledContractStore store) {
        this.store = store;
    }

    /**
     * A record's compiled envelope and substrate profile, and its signed envelope when it has one, each proved to belong
     * to the record.
     *
     * @param compiled the compiled envelope.
     * @param profile  the substrate profile.
     * @param signed   the signed envelope, or null when the record is not signed or it was not asked for.
     */
    record Loaded(CompiledContract compiled, SubstrateProfile profile, SignedFabricContract signed) {
    }

    /**
     * Loads and checks a record's artifacts.
     *
     * @param tenant     the record's tenant.
     * @param record     the record.
     * @param withSigned whether to load and check the signed envelope too; the caller checks
     *                   {@link StudioCompileRecord#signed()} first.
     * @return the checked artifacts.
     * @throws Unavailable   when an artifact is missing, no longer matches its stored hash, or does not parse.
     * @throws Inconsistent  when an artifact does not belong to the record.
     */
    Loaded load(String tenant, StudioCompileRecord record, boolean withSigned) {
        CompiledContract compiled = read(tenant, record, record.compiledEnvelopeSha256(), "compiled envelope",
                compiledCodec::parse);
        String compiledHash = canonicalHash(compiled);
        if (!compiledHash.equals(record.contractHash())) {
            throw new Inconsistent("the compiled envelope of the compile at revision " + record.revision()
                    + " has contract hash " + compiledHash + ", not the record's " + record.contractHash());
        }
        SubstrateProfile profile = read(tenant, record, record.substrateProfileSha256(), "substrate profile",
                profileCodec::parse);
        String profileHash = profileCodec.computeProfileHash(profile);
        if (!profileHash.equals(compiled.substrateProfileHash())) {
            throw new Inconsistent("the substrate profile of the compile at revision " + record.revision()
                    + " has hash " + profileHash + ", but its compiled envelope names " + compiled.substrateProfileHash());
        }
        SignedFabricContract signed = null;
        if (withSigned) {
            if (!record.signed()) {
                throw new IllegalStateException("the record at revision " + record.revision() + " is not signed");
            }
            signed = read(tenant, record, record.signedEnvelopeSha256(), "signed envelope",
                    bytes -> new SignedFabricContractCodec().parse(bytes));
            String carried = canonicalHash(signed.contract());
            if (!record.contractHash().equals(signed.canonicalHash()) || !record.contractHash().equals(carried)) {
                throw new Inconsistent("the signed envelope of the compile at revision " + record.revision()
                        + " is for contract " + signed.canonicalHash() + " (carrying " + carried + "), not the record's "
                        + record.contractHash());
            }
        }
        return new Loaded(compiled, profile, signed);
    }

    /** The canonical hash of a compiled contract: SHA-256 of the bytes Fabric's signer signs. */
    private String canonicalHash(CompiledContract compiled) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(compiledCodec.canonicalBytes(compiled)));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is unavailable", ex);
        }
    }

    /** Loads and parses one stored artifact, or throws {@link Unavailable} naming it. */
    private <T> T read(String tenant, StudioCompileRecord record, String sha256, String what, Function<byte[], T> parser) {
        byte[] bytes = store.get(tenant, sha256).orElseThrow(() -> new Unavailable("the " + what + " of the compile at "
                + "revision " + record.revision() + " (sha256 " + sha256 + ") is not in this Studio's compiled-contract "
                + "store, or no longer matches its hash"));
        try {
            return parser.apply(bytes);
        } catch (RuntimeException ex) {
            throw new Unavailable("the " + what + " of the compile at revision " + record.revision() + " (sha256 "
                    + sha256 + ") does not parse: " + ex.getMessage());
        }
    }

    /** A record's bytes cannot be read; the message names which and why. Answered as COMPILE_RECORD_UNAVAILABLE. */
    static final class Unavailable extends RuntimeException {
        Unavailable(String message) {
            super(message);
        }
    }

    /** A record's bytes do not belong to it; the message names which. Answered as COMPILE_RECORD_INCONSISTENT. */
    static final class Inconsistent extends RuntimeException {
        Inconsistent(String message) {
            super(message);
        }
    }
}
