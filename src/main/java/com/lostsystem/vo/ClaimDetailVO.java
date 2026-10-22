package com.lostsystem.vo;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class ClaimDetailVO {

    private Long id;

    private Long lostPropertyId;

    private String lostPropertyName;

    private Long lostRegisterId;

    private String lostRegisterName;

    private String status;

    private String verificationText;

    private String rejectReason;

    // 当前查看人：FINDER 或 CLAIMANT
    private String viewerRole;

    private LocalDateTime approvedAt;

    private LocalDateTime handoverAt;

    private LocalDateTime completedAt;

    private LocalDateTime createdDate;

    private LocalDateTime updatedDate;
}