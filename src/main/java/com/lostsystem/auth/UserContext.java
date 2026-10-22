package com.lostsystem.auth;

import com.lostsystem.exception.BusinessException;

/**
 * 保存当前请求处理线程上的登录用户。
 *
 * AuthInterceptor 鉴权成功后设置。
 * 同步请求完成或请求转入异步处理时清理。
 *
 * 不会自动传递到线程池或响应式回调线程。
 */
public final class UserContext {

    private static final ThreadLocal<CurrentUser> CURRENT_USER =
            new ThreadLocal<>();

    private UserContext() {
    }

    public static void set(CurrentUser currentUser) {
        if (currentUser == null) {
            throw new IllegalArgumentException("当前用户不能为空");
        }

        CURRENT_USER.set(currentUser);
    }

    public static CurrentUser require() {
        CurrentUser currentUser = CURRENT_USER.get();

        if (currentUser == null) {
            throw new BusinessException(401, "请先登录");
        }

        return currentUser;
    }

    public static CurrentUser get() {
        return CURRENT_USER.get();
    }

    public static void clear() {
        CURRENT_USER.remove();
    }
}