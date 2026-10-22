package com.lostsystem.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

@Data
@TableName("lost_property")
@EqualsAndHashCode(callSuper = true)
public class LostPropertyEntity extends BaseEntity{
    private Long userId;
    private String username;
    private String phone;
    private String lostName;
    private String lostType;
    private String status;

    private Long version;
    private String imageUrl;
    private LocalDateTime returnedAt;
}
