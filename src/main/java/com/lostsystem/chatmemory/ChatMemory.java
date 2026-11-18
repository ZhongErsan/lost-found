package com.lostsystem.chatmemory;

import dev.langchain4j.memory.chat.ChatMemoryProvider;
import dev.langchain4j.store.memory.chat.ChatMemoryStore;
import dev.langchain4j.store.memory.chat.InMemoryChatMemoryStore;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 对话记忆配置类
 * <p>
 * 针对不同业务Agent隔离记忆空间：
 * 一套 ConversationMemory 可以划分多个命名空间（CHAT / INTENTION / LOST_REGISTER / LOST_PROPERTY）
 * 每个命名空间对外暴露独立的 ChatMemoryProvider Bean，供 @AiService 指定使用
 * 不同Agent即使使用相同sessionId，只要provider不同，记忆互相隔离
 * </p>
 */
@Configuration
public class ChatMemory {

    /**
     * 通用对话记忆提供者
     * 对应命名空间：ConversationMemory.CHAT
     */
    @Bean
    public ChatMemoryProvider chatMemoryProvider(ConversationMemory memory) {
        return memory.provider(ConversationMemory.CHAT);
    }

    /**
     * 意图识别专用记忆提供者
     * 对应命名空间：ConversationMemory.INTENTION
     * 就是你前面 AiIntentionAssistant 里配置的 intentionChatMemoryProvider
     */
    @Bean
    public ChatMemoryProvider intentionChatMemoryProvider(ConversationMemory memory) {
        return memory.provider(ConversationMemory.INTENTION);
    }

    /**
     * 失物登记Agent记忆提供者
     * 对应命名空间：ConversationMemory.LOST_REGISTER
     */
    @Bean
    public ChatMemoryProvider lostRegisterChatMemoryProvider(ConversationMemory memory) {
        return memory.provider(ConversationMemory.LOST_REGISTER);
    }

    /**
     * 失物招领Agent记忆提供者
     * 对应命名空间：ConversationMemory.LOST_PROPERTY
     */
    @Bean
    public ChatMemoryProvider lostPropertyChatMemoryProvider(ConversationMemory memory) {
        return memory.provider(ConversationMemory.LOST_PROPERTY);
    }

    /**
     * 会话记忆门面
     * 基于 ChatMemoryStore，支持按命名空间隔离记忆
     * provider(namespace) 本质：在同一个存储里，给记忆key加上命名空间前缀
     */
    @Bean
    public ConversationMemory conversationMemory(ChatMemoryStore store) {
        return new ConversationMemory(store);
    }

    /**
     * 底层记忆存储：内存存储
     * ⚠️ 进程重启、容器重建后所有对话记忆清空，只适合开发调试，生产不能使用
     */
    @Bean
    public ChatMemoryStore chatMemoryStore(){
        return new InMemoryChatMemoryStore();
    }
}