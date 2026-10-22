package com.lostsystem.vo;

import lombok.Data;

@Data
public class ClaimContactVO {

    private Long claimId;

    private String status;

    // 对方身份：FINDER 或 CLAIMANT
    private String counterpartyRole;

    private String realName;

    private String phone;
}