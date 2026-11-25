package com.unfurl.fabric.studio;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.unfurl.deployment.policy.DeploymentPolicy;
import com.unfurl.deployment.serialization.DeploymentPolicyCodec;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;

/**
 * The one rule for whether a compile still describes a Studio draft (LLD-fabric-studio-api, <i>Compile records and
 * freshness</i>). Every route that returns a compile-derived value asks it, so the rule lives in one place.
 *
 * <p>A record is current when it was compiled at the session's current revision and, if the request names the policy
 * it would compile with now, with that same policy. The policy is compared by the hash of the <em>effective</em>
 * deployment policy ({@link StudioDeploymentService#effectivePolicy}), serialized by the deployment module's canonical
 * codec. Fabric is the only side that hashes, so Studio and Fabric cannot canonicalize a policy differently.
 *
 * <p>Pattern: <b>Policy</b> object (a stateless rule with a typed {@link Verdict}).
 */
public final class StudioCompileFreshness {
    /** The verdict codes, as the routes report them. */
    public enum State {
        /** The record describes the current draft with the request's policy. */
        CURRENT,
        /** No compile is recorded for the session. */
        COMPILE_REQUIRED,
        /** The draft, or the policy the request would compile with, has changed since the compile. */
        COMPILE_STALE
    }

    /**
     * A freshness verdict.
     *
     * @param state   the verdict code.
     * @param message what it means for this session and record; empty when current.
     */
    public record Verdict(State state, String message) {
        /** Whether the record is current. */
        public boolean current() {
            return state == State.CURRENT;
        }
    }

    private final StudioDeploymentService deploymentService;
    private final DeploymentPolicyCodec policyCodec = new DeploymentPolicyCodec();

    /**
     * @param deploymentService the service whose effective policy compile resolves with.
     */
    public StudioCompileFreshness(StudioDeploymentService deploymentService) {
        if (deploymentService == null) {
            throw new IllegalArgumentException("deploymentService is required");
        }
        this.deploymentService = deploymentService;
    }

    /**
     * The canonical hash of the effective policy a draft resolves to: SHA-256 of its canonical YAML.
     *
     * @param draft the request's policy draft, or null for Fabric's default policy.
     * @return the lower-case hex hash.
     */
    public String policyHash(StudioDeploymentPolicyDraft draft) {
        DeploymentPolicy effective = deploymentService.effectivePolicy(draft);
        try {
            byte[] canonical = policyCodec.serialize(effective).getBytes(StandardCharsets.UTF_8);
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(canonical));
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("unable to serialize the deployment policy: " + ex.getOriginalMessage(), ex);
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is unavailable", ex);
        }
    }

    /**
     * The session's current compile record: its latest.
     *
     * @param session the draft session.
     * @return the latest record, or empty when nothing was compiled.
     */
    public Optional<StudioCompileRecord> currentRecord(StudioDraftSession session) {
        List<StudioCompileRecord> records = session.compileRecords();
        return records.isEmpty() ? Optional.empty() : Optional.of(records.getLast());
    }

    /**
     * Whether a record still describes the draft.
     *
     * @param session       the draft session the record belongs to.
     * @param record        the record, or null when nothing was compiled.
     * @param requestPolicy the policy the request would compile with now; empty when the request names none, and then
     *                      only the revision is compared.
     * @return the verdict.
     */
    public Verdict of(StudioDraftSession session, StudioCompileRecord record,
                      Optional<StudioDeploymentPolicyDraft> requestPolicy) {
        if (record == null) {
            return new Verdict(State.COMPILE_REQUIRED,
                    "no compile is recorded for session " + session.sessionId() + "; compile the draft first");
        }
        if (record.revision() != session.sceneRevision()) {
            return new Verdict(State.COMPILE_STALE, "compiled at revision " + record.revision()
                    + "; the draft is at revision " + session.sceneRevision());
        }
        if (requestPolicy.isPresent() && !record.deploymentPolicyHash().equals(policyHash(requestPolicy.get()))) {
            return new Verdict(State.COMPILE_STALE, "compiled at revision " + record.revision()
                    + " with a different deployment policy than the one this request names");
        }
        return new Verdict(State.CURRENT, "");
    }
}
