package com.lostsystem.vo;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class ChatConversationListItemVO {

    // 会话表主键，用于列表翻页
    private Long id;

    // 查询历史和继续聊天使用的会话标识
    private String sessionId;

    // 第一条用户消息的前40个字符
    private String title;

    private LocalDateTime createdDate;

    // 最近一条已保存消息的时间；没有消息时为 null
    private LocalDateTime lastMessageAt;
    // lost、found、search；旧会话可能为 null
    private String mode;
}