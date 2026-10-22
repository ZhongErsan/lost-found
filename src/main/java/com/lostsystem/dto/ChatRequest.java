package com.lostsystem.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record ChatRequest(

        @NotBlank(message = "聊天会话标识不能为空")
        @Size(max = 64, message = "聊天会话标识不能超过64个字符")
        String sessionId,

        @NotBlank(message = "消息不能为空")
        @Size(max = 1500, message = "消息不能超过1500个字符")
        String message
) {
}