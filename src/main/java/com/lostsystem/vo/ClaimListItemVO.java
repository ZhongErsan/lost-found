package com.lostsystem.vo;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class ClaimListItemVO {

    private Long id;

    private Long lostPropertyId;

    private String lostPropertyName;

    private Long lostRegisterId;

    private String lostRegisterName;

    // 认领申请状态
    private String status;

    private String verificationText;

    private String rejectReason;

    private LocalDateTime createdDate;

    private LocalDateTime updatedDate;
}