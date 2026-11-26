package com.unfurl.fabric.studio;

import java.util.List;

/**
 * One section of a Studio view projection (LLD-fabric-studio-api, <i>StudioViewProjection</i>): the items Fabric read,
 * or why there are none.
 *
 * <p>Pattern: immutable <b>value object</b> with an invariant: {@code items} is non-empty exactly when
 * {@code available} is true. A section with nothing to show is unavailable with its reason, never an empty list, so
 * Studio never draws an empty layer (Studio LLD §1 rule 2).
 *
 * @param available    whether the section has items.
 * @param absentReason why not, when unavailable.
 * @param provenance   the compile record a compile-derived section was read from; null for a claim-derived section.
 * @param items        the items, each naming its {@code /dynamic-dcp} node and its DCP source path.
 * @param <T>          the item type.
 */
public record StudioViewSection<T>(boolean available, StudioAbsentReason absentReason,
                                   StudioCompileProvenance provenance, List<T> items) {
    /** Compact constructor: enforces the invariant and freezes the items. */
    public StudioViewSection {
        items = items == null ? List.of() : List.copyOf(items);
        if (available != !items.isEmpty() || available == (absentReason != null)) {
            throw new IllegalArgumentException("a section has items and no absent reason, or no items and a reason");
        }
    }

    /**
     * Factory: the section of these items, or unavailable with the given reason when there are none.
     *
     * @param items      the items read.
     * @param emptyCode  the reason code when there are none, for example {@code NO_OWNED_STATE_DECLARED}.
     * @param emptyText  the reason message when there are none.
     * @param provenance the compile record, for a compile-derived section; otherwise null.
     * @param <T>        the item type.
     * @return the section.
     */
    public static <T> StudioViewSection<T> of(List<T> items, String emptyCode, String emptyText,
                                              StudioCompileProvenance provenance) {
        return items == null || items.isEmpty()
                ? new StudioViewSection<>(false, new StudioAbsentReason(emptyCode, emptyText), provenance, List.of())
                : new StudioViewSection<>(true, null, provenance, items);
    }

    /**
     * Factory: an unavailable section.
     *
     * @param reason     why.
     * @param provenance the compile record it concerns, when there is one; otherwise null.
     * @param <T>        the item type.
     * @return the section.
     */
    public static <T> StudioViewSection<T> absent(StudioAbsentReason reason, StudioCompileProvenance provenance) {
        return new StudioViewSection<>(false, reason, provenance, List.of());
    }
}
