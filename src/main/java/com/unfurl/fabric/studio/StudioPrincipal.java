package com.unfurl.fabric.studio;

import java.util.Arrays;
import java.util.Set;
import java.util.TreeSet;

public record StudioPrincipal(
        String userId,
        String routeTenant,
        String assertedTenant,
        Set<String> tenantMemberships
) {
    public StudioPrincipal {
        userId = userId == null ? "" : userId.trim();
        routeTenant = routeTenant == null ? "" : routeTenant.trim();
        assertedTenant = assertedTenant == null ? "" : assertedTenant.trim();
        tenantMemberships = tenantMemberships == null ? Set.of() : Set.copyOf(tenantMemberships);
    }

    public static StudioPrincipal fromHeaders(
            String routeTenant,
            String assertedTenant,
            String userId,
            String tenantMemberships
    ) {
        return new StudioPrincipal(userId, routeTenant, assertedTenant, parseMemberships(tenantMemberships));
    }

    private static Set<String> parseMemberships(String value) {
        if (value == null || value.isBlank()) {
            return Set.of();
        }
        Set<String> memberships = new TreeSet<>();
        Arrays.stream(value.split(","))
                .map(String::trim)
                .filter(item -> !item.isBlank())
                .forEach(memberships::add);
        return memberships;
    }
}
