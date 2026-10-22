package com.lostsystem.chatmemory;

import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.store.memory.chat.InMemoryChatMemoryStore;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ConversationMemoryTests {

    @Test
    void clearingOneSessionClearsAllItsScopesAndLegacyKeyButKeepsOtherSessions() {
        var store = new InMemoryChatMemoryStore();
        var memory = new ConversationMemory(store);
        var scopes = List.of(ConversationMemory.CHAT, ConversationMemory.INTENTION,
                ConversationMemory.LOST_REGISTER, ConversationMemory.LOST_PROPERTY);
        for (String scope : scopes) {
            memory.provider(scope).get("a").add(SystemMessage.from(scope));
            memory.provider(scope).get("a").add(UserMessage.from("用户甲"));
            memory.provider(scope).get("b").add(UserMessage.from("用户乙"));
        }
        store.updateMessages("a", List.of(UserMessage.from("旧版本记录")));
        memory.clear("a");
        for (String scope : scopes) {
            assertTrue(memory.provider(scope).get("a").messages().isEmpty());
            assertFalse(memory.provider(scope).get("b").messages().isEmpty());
        }
        assertTrue(store.getMessages("a").isEmpty());
    }
}
