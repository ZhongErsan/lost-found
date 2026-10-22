package com.lostsystem.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@TableName("chat_history")
@EqualsAndHashCode(callSuper = true)
public class ChatHistoryEntity extends BaseEntity{
    private String sessionId;
    private String role;
    private String content;
}
