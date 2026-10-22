package com.lostsystem.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * 普通用户注册请求。
 * 密码只在请求对象中出现，持久化前必须经过 PasswordEncoder。
 */
public record RegisterRequest(
        @NotBlank(message = "用户名不能为空")
        @Size(min = 3, max = 32, message = "用户名长度必须在 3 到 32 个字符之间")
        @Pattern(
                regexp = "^[A-Za-z0-9._-]+$",
                message = "用户名只能包含字母、数字、点、下划线和连字符"
        )
        String username,

        @NotBlank(message = "密码不能为空")
        @Size(min = 8, max = 72, message = "密码长度必须在 8 到 72 个字符之间")
        String password,

        @NotBlank(message = "姓名不能为空")
        @Size(max = 50, message = "姓名不能超过 50 个字符")
        String realName,

        @NotBlank(message = "手机号不能为空")
        @Size(max = 20, message = "手机号不能超过 20 个字符")
        @Pattern(
                regexp = "^\\+?[0-9 ()-]{7,20}$",
                message = "手机号格式不正确"
        )
        String phone
) {
}
