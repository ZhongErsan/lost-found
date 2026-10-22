package com.lostsystem.vo;

import com.lostsystem.entity.ItemClaimEntity;

public record ClaimSubmissionVO(
        Long id,
        Long lostPropertyId,
        Long lostRegisterId,
        String status,
        String verificationText
) {

    public static ClaimSubmissionVO from(ItemClaimEntity entity) {
        return new ClaimSubmissionVO(
                entity.getId(),
                entity.getLostPropertyId(),
                entity.getLostRegisterId(),
                entity.getStatus(),
                entity.getVerificationText()
        );
    }
}