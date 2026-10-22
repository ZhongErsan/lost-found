package com.lostsystem.vo;

import com.lostsystem.entity.ChatHistoryEntity;

import java.time.LocalDateTime;

public record ChatHistoryVO(Long id, String sessionId, String role, String content,
                            LocalDateTime createdDate, LocalDateTime updatedDate) {

    public static ChatHistoryVO from(ChatHistoryEntity entity) {
        return new ChatHistoryVO(entity.getId(), entity.getSessionId(), entity.getRole(),
                entity.getContent(), entity.getCreatedDate(), entity.getUpdatedDate());
    }
}
