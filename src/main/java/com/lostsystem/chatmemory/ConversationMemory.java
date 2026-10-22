package com.lostsystem.chatmemory;

import dev.langchain4j.memory.chat.ChatMemoryProvider;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import dev.langchain4j.store.memory.chat.ChatMemoryStore;

import java.util.List;

/** 同一个 sessionId 在不同业务中使用不同的存储键，防止系统提示词互相覆盖。 */
public class ConversationMemory {

    public static final String CHAT = "chat";
    public static final String INTENTION = "intention";
    public static final String LOST_REGISTER = "lost-register";
    public static final String LOST_PROPERTY = "lost-property";
    private static final List<String> SCOPES = List.of(CHAT, INTENTION, LOST_REGISTER, LOST_PROPERTY);

    private final ChatMemoryStore store;

    public ConversationMemory(ChatMemoryStore store) {
        this.store = store;
    }

    public ChatMemoryProvider provider(String scope) {
        if (!SCOPES.contains(scope)) throw new IllegalArgumentException("未知的会话业务：" + scope);
        return sessionId -> MessageWindowChatMemory.builder()
                .id(new ScopedMemoryId(scope, sessionId))
                .maxMessages(40)
                .chatMemoryStore(store)
                .build();
    }

    public void clear(String sessionId) {
        SCOPES.forEach(scope -> store.deleteMessages(new ScopedMemoryId(scope, sessionId)));
        store.deleteMessages(sessionId);
    }

    private record ScopedMemoryId(String scope, Object sessionId) {
    }
}
