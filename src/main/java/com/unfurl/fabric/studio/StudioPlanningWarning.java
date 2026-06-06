package com.unfurl.fabric.studio;

public record StudioPlanningWarning(String code, String message) {
    public StudioPlanningWarning {
        code = code == null ? "" : code;
        message = message == null ? "" : message;
    }
}
