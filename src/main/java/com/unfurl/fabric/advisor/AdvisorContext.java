package com.unfurl.fabric.advisor;

import com.unfurl.fabric.catalog.Catalog;
import com.unfurl.fabric.matcher.MatchResult;
import com.unfurl.fabric.needs.Need;

public record AdvisorContext(
        Catalog catalog,
        Need need,
        MatchResult matchResult
) {
    public AdvisorContext {
        if (catalog == null) {
            throw new IllegalArgumentException("catalog is required");
        }
        if (need == null) {
            throw new IllegalArgumentException("need is required");
        }
        if (matchResult == null) {
            throw new IllegalArgumentException("matchResult is required");
        }
    }
}
