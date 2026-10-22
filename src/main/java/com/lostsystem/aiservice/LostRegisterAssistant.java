package com.lostsystem.aiservice;

import com.lostsystem.aioutput.LostRegisterOutput;
import dev.langchain4j.service.MemoryId;
import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;
import dev.langchain4j.service.V;
import dev.langchain4j.service.spring.AiService;
import dev.langchain4j.service.spring.AiServiceWiringMode;

/** 丢失物品的登记与修改共用提示词和业务记忆。 */
@AiService(wiringMode = AiServiceWiringMode.EXPLICIT,
        chatModel = "openAiChatModel",
        chatMemoryProvider = "lostRegisterChatMemoryProvider",
        tools = {"chatHistoryTools", "lostRegisterTools"})
public interface LostRegisterAssistant {
    @SystemMessage(fromResource = "/registerlost.txt")
    @UserMessage("""
    当前聊天会话：{{sessionId}}

    当前登录用户的默认登记联系人：
    姓名：{{contactName}}
    手机号：{{contactPhone}}

    用户当前消息：
    {{message}}
    """)
    LostRegisterOutput registerLost(
            @MemoryId @V("sessionId") String sessionId,
            @V("message") String message,
            @V("contactName") String contactName,
            @V("contactPhone") String contactPhone
    );
}
