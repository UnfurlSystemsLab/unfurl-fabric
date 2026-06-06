package com.unfurl.fabric.studio;

public record StudioAccessDecision(boolean allowed, String reason) {
    public StudioAccessDecision {
        reason = reason == null ? "" : reason;
    }

    public static StudioAccessDecision allow() {
        return new StudioAccessDecision(true, "");
    }

    public static StudioAccessDecision deny(String reason) {
        return new StudioAccessDecision(false, reason);
    }
}
