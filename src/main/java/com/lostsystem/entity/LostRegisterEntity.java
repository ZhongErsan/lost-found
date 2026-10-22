package com.lostsystem.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

@Data
@TableName("lost_register")
@EqualsAndHashCode(callSuper = true)
public class LostRegisterEntity extends BaseEntity{


    private Long userId;
    private String username;

    private String phone;

    private String lostName;

    private String lostType;
    private String status;

    private Long version;
    private String imageUrl;
    private LocalDateTime recoveredAt;
}