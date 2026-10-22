package com.lostsystem.aiservice;

import com.lostsystem.config.ChatConfig;
import dev.langchain4j.service.MemoryId;
import dev.langchain4j.service.UserMessage;
import dev.langchain4j.service.spring.AiService;
import dev.langchain4j.service.spring.AiServiceWiringMode;
import reactor.core.publisher.Flux;

@AiService(wiringMode = AiServiceWiringMode.EXPLICIT,
        chatModel = "openAiChatModel",
        streamingChatModel = ChatConfig.DEEPSEEK_STREAMING_CHAT_BEAN,
        chatMemoryProvider = "chatMemoryProvider",
        tools = {"chatHistoryTools"})
public interface AiAssistant {

    String chat(@MemoryId String id, @UserMessage String message);
    Flux<String> chatStream(@MemoryId String id,@UserMessage String message);

}
