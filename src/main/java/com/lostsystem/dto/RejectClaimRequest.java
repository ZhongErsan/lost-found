package com.lostsystem.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record RejectClaimRequest(

        @NotBlank(message = "请填写拒绝原因")
        @Size(max = 255, message = "拒绝原因不能超过255个字符")
        String rejectReason

) {
}