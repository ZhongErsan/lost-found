package com.lostsystem.auth;

public record RegistrationContact(
        Long userId,
        String realName,
        String phone
) {
    public boolean hasName() {
        return realName != null && !realName.isBlank();
    }

    public boolean hasPhone() {
        return phone != null && !phone.isBlank();
    }

    public boolean complete() {
        return hasName() && hasPhone();
    }
}