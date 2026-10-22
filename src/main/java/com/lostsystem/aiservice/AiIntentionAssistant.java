package com.lostsystem.aiservice;

import com.lostsystem.aioutput.IntentionOutput;
import dev.langchain4j.service.MemoryId;
import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;
import dev.langchain4j.service.V;
import dev.langchain4j.service.spring.AiService;
import dev.langchain4j.service.spring.AiServiceWiringMode;

@AiService(wiringMode = AiServiceWiringMode.EXPLICIT,
        chatModel = "openAiChatModel",
        chatMemoryProvider = "intentionChatMemoryProvider",
        tools = "chatHistoryTools")
public interface AiIntentionAssistant {

    @SystemMessage(fromResource = "/getIntention.txt")
    @UserMessage("当前sessionId:{{sessionId}};用户当前消息：{{message}}")
    IntentionOutput intention(@MemoryId @V("sessionId") String sessionId,
                              @V("message") String message);
}
