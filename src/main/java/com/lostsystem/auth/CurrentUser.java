package com.lostsystem.auth;

import com.lostsystem.entity.SysUser;

/**
 * 当前请求对应的登录用户。
 *
 * 这个对象只保存在当前请求的 ThreadLocal 中，
 * 不应该直接返回给前端作为数据库实体。
 */
public record CurrentUser(
        Long userId,
        String username,
        String realName,
        String role,
        String loginSessionId
) {

    public static CurrentUser from(
            SysUser user,
            String loginSessionId
    ) {
        return new CurrentUser(
                user.getId(),
                user.getUsername(),
                user.getRealName(),
                user.getRole(),
                loginSessionId
        );
    }

    public boolean isAdmin() {
        return "ADMIN".equalsIgnoreCase(role);
    }

    public boolean isUser() {
        return "USER".equalsIgnoreCase(role);
    }
}