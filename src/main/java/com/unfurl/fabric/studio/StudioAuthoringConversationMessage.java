package com.unfurl.fabric.studio;

public record StudioAuthoringConversationMessage(String role, String content) {
    public StudioAuthoringConversationMessage {
        role = role == null || role.isBlank() ? "USER" : role;
        content = content == null ? "" : content;
    }
}
