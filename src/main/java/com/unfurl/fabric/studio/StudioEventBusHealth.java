package com.unfurl.fabric.studio;

public record StudioEventBusHealth(String provider, String status, String detail) {
    public StudioEventBusHealth {
        provider = provider == null || provider.isBlank() ? "in-memory" : provider;
        status = status == null || status.isBlank() ? "UNKNOWN" : status;
        detail = detail == null ? "" : detail;
    }
}
