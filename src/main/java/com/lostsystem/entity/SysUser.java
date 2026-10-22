package com.lostsystem.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

@Data
@TableName("sys_user")
@EqualsAndHashCode(callSuper = true)
public class SysUser extends BaseEntity{


    /**
     * 登录用户名，数据库中应该建立唯一索引。
     */
    private String username;

    /**
     * BCrypt 密码哈希。
     *
     * 这里不能保存明文密码。
     */
    private String password;

    /**
     * 展示名称。
     */
    @TableField("real_name")
    private String realName;

    /**
     * 用户联系电话。
     */
    private String phone;

    /**
     * USER 或 ADMIN。
     */
    private String role;

    /**
     * ACTIVE 或 DISABLED。
     */
    private String status;

}