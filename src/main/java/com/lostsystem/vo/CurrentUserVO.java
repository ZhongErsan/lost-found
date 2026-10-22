package com.lostsystem.vo;

import com.lostsystem.auth.CurrentUser;

public record CurrentUserVO(
        Long userId,
        String username,
        String realName,
        String role
) {

    public static CurrentUserVO from(CurrentUser currentUser) {
        return new CurrentUserVO(
                currentUser.userId(),
                currentUser.username(),
                currentUser.realName(),
                currentUser.role()
        );
    }
}