package com.lostsystem.dto;

public record ChatStreamEvent(
        String type,
        String content,
        String registrationType,
        Long registrationId,
        String action
) {
    public static ChatStreamEvent text(String content) {
        return new ChatStreamEvent("text", content, null, null, null);
    }

    public static ChatStreamEvent registrationSaved(
            String registrationType,
            Long registrationId,
            String action
    ) {
        return new ChatStreamEvent(
                "registration_saved",
                null,
                registrationType,
                registrationId,
                action
        );
    }
}