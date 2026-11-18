package com.lostsystem.chatmemory;

import dev.langchain4j.memory.chat.ChatMemoryProvider;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import dev.langchain4j.store.memory.chat.ChatMemoryStore;

import java.util.List;

/**
 * 会话记忆管理器
 * 核心设计：同一个 sessionId 在不同业务中使用不同的存储键，防止不同业务的系统提示词、对话上下文互相覆盖干扰。
 * 采用【业务域scope + sessionId】组合成复合记忆主键 ScopedMemoryId，隔离多套对话记忆。
 */
public class ConversationMemory {
    /** 通用闲聊对话记忆域 */
    public static final String CHAT = "chat";
    /** 意图识别对话记忆域 */
    public static final String INTENTION = "intention";
    /** 失物登记（lost）对话记忆域 */
    public static final String LOST_REGISTER = "lost-register";
    /** 拾物登记（found）对话记忆域 */
    public static final String LOST_PROPERTY = "lost-property";

    /** 合法业务scope白名单，用来校验入参 */
    private static final List<String> SCOPES = List.of(CHAT, INTENTION, LOST_REGISTER, LOST_PROPERTY);

    /** 底层记忆存储实现（Redis/内存等） */
    private final ChatMemoryStore store;

    public ConversationMemory(ChatMemoryStore store) {
        this.store = store;
    }

    /**
     * 获取指定业务域的 ChatMemoryProvider
     * @param scope 业务记忆域标识
     * @return 记忆提供者，根据sessionId产出独立隔离的对话记忆实例
     */
    public ChatMemoryProvider provider(String scope) {
        // 校验scope是否在白名单内，防止非法业务域
        if (!SCOPES.contains(scope))
            throw new IllegalArgumentException("未知的会话业务：" + scope);
        return sessionId -> MessageWindowChatMemory.builder()
                // 复合主键：scope + sessionId，实现记忆隔离
                .id(new ScopedMemoryId(scope, sessionId))
                // 单条记忆窗口最多保留40条消息，控制上下文长度，避免token超限
                .maxMessages(40)
                .chatMemoryStore(store)
                .build();
    }

    /**
     * 清空某个sessionId下【所有业务域】的对话记忆
     * @param sessionId 会话ID
     */
    public void clear(String sessionId) {
        // 遍历全部scope，逐个删除该会话下各业务隔离记忆
        SCOPES.forEach(scope -> store.deleteMessages(new ScopedMemoryId(scope, sessionId)));
        // 兜底：清理直接以sessionId为key的无scope记忆
        store.deleteMessages(sessionId);
    }

    /**
     * 带业务域的复合记忆ID
     * record 重写了equals和hashCode，保证 scope+sessionId 一起作为唯一键
     * @param scope 业务域
     * @param sessionId 会话标识
     */
    private record ScopedMemoryId(String scope, Object sessionId) {
    }
}

