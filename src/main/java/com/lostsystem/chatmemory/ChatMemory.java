package com.lostsystem.chatmemory;

import dev.langchain4j.memory.chat.ChatMemoryProvider;
import dev.langchain4j.store.memory.chat.ChatMemoryStore;
import dev.langchain4j.store.memory.chat.InMemoryChatMemoryStore;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class ChatMemory {
    @Bean
    public ChatMemoryProvider chatMemoryProvider(ConversationMemory memory) {
        return memory.provider(ConversationMemory.CHAT);
    }

    @Bean
    public ChatMemoryProvider intentionChatMemoryProvider(ConversationMemory memory) {
        return memory.provider(ConversationMemory.INTENTION);
    }

    @Bean
    public ChatMemoryProvider lostRegisterChatMemoryProvider(ConversationMemory memory) {
        return memory.provider(ConversationMemory.LOST_REGISTER);
    }

    @Bean
    public ChatMemoryProvider lostPropertyChatMemoryProvider(ConversationMemory memory) {
        return memory.provider(ConversationMemory.LOST_PROPERTY);
    }

    @Bean
    public ConversationMemory conversationMemory(ChatMemoryStore store) {
        return new ConversationMemory(store);
    }
    @Bean
    public ChatMemoryStore chatMemoryStore(){
        return new InMemoryChatMemoryStore();
    }
}
