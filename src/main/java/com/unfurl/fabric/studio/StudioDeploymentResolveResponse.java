package com.unfurl.fabric.studio;

import com.unfurl.fabric.matcher.MatchFailureReason;
import com.unfurl.fabric.matcher.TrustRejectionReason;

import java.util.List;

/**
 * Data Transfer Object: the result of {@code POST /studio/deployment/resolve}, either the resolved deployment shapes or
 * why the draft could not be resolved.
 *
 * <p>Pattern: immutable DTO with {@link #resolved} and {@link #invalid} factories. A {@code NO_MATCH} keeps its
 * {@code details} text and also carries the typed causes, {@code reasons} and {@code trustRejections}, which
 * {@link com.unfurl.fabric.matcher.NoMatchExplanation} builds from the same causes as that text
 * (LLD-fabric-studio-api, <i>Structured match failures and trust rejections</i>). Both lists are empty for any other
 * status.
 *
 * @param status          {@code RESOLVED} or {@code INVALID}.
 * @param candidateId     the resolved candidate's id, or null when invalid.
 * @param selections      the selected deployment shapes per component.
 * @param warnings        planning warnings.
 * @param reason          the invalid reason code, for example {@code NO_MATCH}.
 * @param details         the invalid reason's text.
 * @param rejectedShapes  rendered shape rejections from a failed deployment resolution.
 * @param reasons         the typed causes of a {@code NO_MATCH}.
 * @param trustRejections the trust rejections a filesystem {@code NO_MATCH} considered.
 */
public record StudioDeploymentResolveResponse(
        String status,
        String candidateId,
        List<StudioDeploymentSelection> selections,
        List<String> warnings,
        String reason,
        String details,
        List<String> rejectedShapes,
        List<MatchFailureReason> reasons,
        List<TrustRejectionReason> trustRejections) {

    /** Compact constructor: freezes every list, absent ones as empty. */
    public StudioDeploymentResolveResponse {
        selections = selections == null ? List.of() : List.copyOf(selections);
        warnings = warnings == null ? List.of() : List.copyOf(warnings);
        rejectedShapes = rejectedShapes == null ? List.of() : List.copyOf(rejectedShapes);
        reasons = reasons == null ? List.of() : List.copyOf(reasons);
        trustRejections = trustRejections == null ? List.of() : List.copyOf(trustRejections);
    }

    /** Compatibility constructor: a response without typed causes (anything but a {@code NO_MATCH}). */
    public StudioDeploymentResolveResponse(
            String status,
            String candidateId,
            List<StudioDeploymentSelection> selections,
            List<String> warnings,
            String reason,
            String details,
            List<String> rejectedShapes) {
        this(status, candidateId, selections, warnings, reason, details, rejectedShapes, List.of(), List.of());
    }

    /**
     * This response with its selections replaced, for example attributed to the draft's catalog entries in session mode
     * ({@link StudioDeploymentSelection#withCatalogEntryId}); every other field is kept.
     *
     * @param attributed the selections to carry instead.
     * @return a copy; this response is unchanged.
     */
    public StudioDeploymentResolveResponse withSelections(List<StudioDeploymentSelection> attributed) {
        return new StudioDeploymentResolveResponse(status, candidateId, attributed, warnings, reason, details, rejectedShapes,
                reasons, trustRejections);
    }

    /** Factory: a resolved response. */
    public static StudioDeploymentResolveResponse resolved(
            String candidateId,
            List<StudioDeploymentSelection> selections,
            List<String> warnings) {
        return new StudioDeploymentResolveResponse(
                "RESOLVED",
                candidateId,
                selections,
                warnings,
                null,
                null,
                List.of());
    }

    /** Factory: an invalid response without typed causes. */
    public static StudioDeploymentResolveResponse invalid(
            String reason,
            String details,
            List<String> rejectedShapes) {
        return invalid(reason, details, rejectedShapes, List.of(), List.of());
    }

    /** Factory: an invalid response with the typed causes of a {@code NO_MATCH}. */
    public static StudioDeploymentResolveResponse invalid(
            String reason,
            String details,
            List<String> rejectedShapes,
            List<MatchFailureReason> reasons,
            List<TrustRejectionReason> trustRejections) {
        return new StudioDeploymentResolveResponse(
                "INVALID",
                null,
                List.of(),
                List.of(),
                reason,
                details,
                rejectedShapes,
                reasons,
                trustRejections);
    }
}
