package com.lostsystem.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

@Data
@TableName("item_claim")
@EqualsAndHashCode(callSuper = true)
public class ItemClaimEntity extends BaseEntity {

    // 拾物登记编号
    private Long lostPropertyId;

    // 申请人自己的丢失登记编号
    private Long lostRegisterId;

    // 申请认领的用户编号
    private Long claimantUserId;

    // 认领依据
    private String verificationText;

    // PENDING、APPROVED、HANDOVER_PENDING、
    // COMPLETED、REJECTED、CANCELLED
    private String status;

    private String rejectReason;

    private LocalDateTime approvedAt;

    private LocalDateTime handoverAt;

    private LocalDateTime completedAt;
}