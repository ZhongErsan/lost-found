package com.lostsystem.dto;

/**
 * 流式输出事件对象
 * <p>
 * 用于 SSE / WebSocket 流式推送，区分不同类型消息分片：文本流、登记落库回调事件等
 * 采用 record 声明，自动提供 equals、hashCode、toString、全参构造、getter
 * </p>
 *
 * @param type            事件类型：text=文本分片；registration_saved=登记数据持久化完成事件
 * @param content         文本内容，仅 type=text 时有效
 * @param registrationType 登记类型，对应失物/拾物业务标识，registration_saved 事件使用
 * @param registrationId  入库后生成的登记主键ID，registration_saved 事件使用
 * @param action          动作标识，例如 create/update，用于前端区分操作行为
 */
public record ChatStreamEvent(
        String type,
        String content,
        String registrationType,
        Long registrationId,
        String action
) {

    /**
     * 构建文本分片事件
     * 模型流式返回文字内容时使用，前端逐块拼接输出
     *
     * @param content 文本片段
     * @return text 类型流式事件
     */
    public static ChatStreamEvent text(String content) {
        return new ChatStreamEvent("text", content, null, null, null);
    }

    /**
     * 构建【登记保存完成】事件
     * 业务侧数据库落库成功后推送，通知前端：登记记录已生成，携带ID、类型和操作动作
     * 不携带LLM文本内容，用于前端跳转、渲染卡片等交互
     *
     * @param registrationType 登记类型（失物登记/拾物登记）
     * @param registrationId   数据库主键
     * @param action           操作动作，如 create
     * @return registration_saved 类型事件
     */
    public static ChatStreamEvent registrationSaved(
            String registrationType,
            Long registrationId,
            String action
    ) {
        return new ChatStreamEvent(
                "registration_saved",
                null,
                registrationType,
                registrationId,
                action
        );
    }
}
