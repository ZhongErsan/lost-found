package com.lostsystem.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

public record CreateClaimRequest(

        @NotNull(message = "请选择要认领的拾物登记")
        @Positive(message = "拾物登记编号不正确")
        Long lostPropertyId,

        @NotNull(message = "请选择自己的丢失登记")
        @Positive(message = "丢失登记编号不正确")
        Long lostRegisterId,

        @NotBlank(message = "请填写认领依据")
        @Size(max = 1000, message = "认领依据不能超过1000个字符")
        String verificationText

) {
}