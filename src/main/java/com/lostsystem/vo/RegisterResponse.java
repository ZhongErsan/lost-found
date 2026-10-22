package com.lostsystem.vo;

/**
 * 注册成功后的公开用户信息，不返回密码、密码哈希或登录凭证。
 */
public record RegisterResponse(
        Long userId,
        String username,
        String realName,
        String phone,
        String role
) {
}
