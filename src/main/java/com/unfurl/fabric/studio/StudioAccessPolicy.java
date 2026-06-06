package com.unfurl.fabric.studio;

public final class StudioAccessPolicy {
    private final boolean enforceTenantMembership;

    public StudioAccessPolicy(boolean enforceTenantMembership) {
        this.enforceTenantMembership = enforceTenantMembership;
    }

    public StudioAccessDecision authorize(StudioPrincipal principal) {
        if (!enforceTenantMembership) {
            return StudioAccessDecision.allow();
        }
        if (principal.assertedTenant().isBlank() || !principal.routeTenant().equals(principal.assertedTenant())) {
            return StudioAccessDecision.deny("tenant header does not match route tenant");
        }
        if (principal.userId().isBlank()) {
            return StudioAccessDecision.deny("authenticated Studio user is required");
        }
        if (!principal.tenantMemberships().contains(principal.routeTenant())) {
            return StudioAccessDecision.deny("Studio user is not a member of route tenant");
        }
        return StudioAccessDecision.allow();
    }
}
