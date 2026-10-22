package com.lostsystem.auth;

/**
 * JWT 解析后的身份信息。
 *
 * @param userId          数据库用户 ID
 * @param loginSessionId  本次登录会话 ID
 */
public record JwtIdentity(
        Long userId,
        String loginSessionId
) {
}