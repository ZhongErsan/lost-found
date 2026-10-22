package com.lostsystem.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

public record SetConversationModeRequest(

        @NotBlank(message = "请选择会话模式")
        @Pattern(
                regexp = "^(lost|found|search)$",
                message = "会话模式只能是lost、found或search"
        )
        String mode

) {
}