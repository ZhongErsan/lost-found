package com.lostsystem.vo;

import com.lostsystem.entity.ChatHistoryEntity;

import java.time.LocalDateTime;
/**
 * 聊天记录VO（使用Java Record，不可变数据载体）
 * 用于向前端返回单条对话记录
 *
 * @param id          主键id
 * @param sessionId   所属会话唯一标识
 * @param role        消息角色：user 用户消息 / assistant AI回复
 * @param content     对话文本内容
 * @param createdDate 创建时间
 * @param updatedDate 更新时间
 */
public record ChatHistoryVO(
        Long id,
        String sessionId,
        String role,
        String content,
        LocalDateTime createdDate,
        LocalDateTime updatedDate) {

    /**
     * 实体转VO静态工厂方法
     * 将数据库 ChatHistoryEntity 对象转换成对外输出的VO对象
     *
     * @param entity 数据库实体
     * @return 封装好的 ChatHistoryVO
     */
    public static ChatHistoryVO from(ChatHistoryEntity entity) {
        return new ChatHistoryVO(
                entity.getId(),
                entity.getSessionId(),
                entity.getRole(),
                entity.getContent(),
                entity.getCreatedDate(),
                entity.getUpdatedDate());
    }
}
