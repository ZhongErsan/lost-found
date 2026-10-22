package com.lostsystem.vo;

public record LoginResponse(
        String token,
        Long userId,
        String username,
        String realName,
        String role
) {
}