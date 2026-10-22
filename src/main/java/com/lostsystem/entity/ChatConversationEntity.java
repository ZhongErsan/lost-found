package com.lostsystem.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("chat_conversation")
public class ChatConversationEntity extends BaseEntity {

    /**
     * 聊天会话标识。
     */
    private String sessionId;

    /**
     * 会话所属登录用户的 ID。
     */
    private Long userId;
    /**
     * lost、found、search。
     * 历史会话可能为 null。
     */
    private String mode;
}