package com.lostsystem.aiservice;

import com.lostsystem.chatmemory.ConversationMemory;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import dev.langchain4j.service.AiServices;
import dev.langchain4j.store.memory.chat.InMemoryChatMemoryStore;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class AssistantConversationTests {

    @Test
    void foundAssistantUsesSynchronousModelForRegistrationAndStreamingModelForQuery() {
        ChatModel model = mock(ChatModel.class);
        when(model.chat(any(ChatRequest.class)))
                .thenReturn(response("{\"output\":\"请补充信息\",\"completed\":false}"));
        StreamingChatModel streamingModel = mock(StreamingChatModel.class);
        doAnswer(call -> {
            StreamingChatResponseHandler handler = call.getArgument(1);
            handler.onPartialResponse("找到");
            handler.onPartialResponse("匹配记录");
            handler.onCompleteResponse(response("找到匹配记录"));
            return null;
        }).when(streamingModel).chat(any(ChatRequest.class), any(StreamingChatResponseHandler.class));
        LostPropertyAssistant assistant = AiServices.builder(LostPropertyAssistant.class)
                .chatModel(model)
                .streamingChatModel(streamingModel)
                .chatMemoryProvider(new ConversationMemory(new InMemoryChatMemoryStore())
                        .provider(ConversationMemory.LOST_PROPERTY))
                .build();

        assertEquals("请补充信息", assistant.registerLostProperty("register-a", "捡到钱包", "账号姓名", "138****0000").getOutput());
        assertEquals("找到匹配记录", assistant.queryLostProperty("query-a", "查找黑色钱包", "[]")
                .collectList().block().stream().collect(java.util.stream.Collectors.joining()));
        verify(model).chat(any(ChatRequest.class));
        verify(streamingModel).chat(any(ChatRequest.class), any(StreamingChatResponseHandler.class));
    }

    @Test
    void registrationKeepsEarlierTurnsWhileIntentionAndOtherSessionsRemainIsolated() {
        ConversationMemory memory = new ConversationMemory(new InMemoryChatMemoryStore());
        List<List<ChatMessage>> registerRequests = new ArrayList<>();
        ChatModel model = mock(ChatModel.class);
        when(model.chat(any(ChatRequest.class))).thenAnswer(call -> {
            registerRequests.add(List.copyOf(call.getArgument(0, ChatRequest.class).messages()));
            return response("{\"output\":\"请继续补充\",\"completed\":false}");
        });
        LostRegisterAssistant register = AiServices.builder(LostRegisterAssistant.class)
                .chatModel(model).chatMemoryProvider(memory.provider(ConversationMemory.LOST_REGISTER)).build();
        ChatModel intentionModel = mock(ChatModel.class);
        when(intentionModel.chat(any(ChatRequest.class)))
                .thenReturn(response("{\"intention\":1,\"output\":\"继续登记\"}"));
        AiIntentionAssistant intention = AiServices.builder(AiIntentionAssistant.class)
                .chatModel(intentionModel).chatMemoryProvider(memory.provider(ConversationMemory.INTENTION)).build();

        register.registerLost("session-a", "我丢了黑色钱包，姓名张三", "账号姓名", "138****0000");
        intention.intention("session-a", "路由识别消息，不应进入登记记忆");
        register.registerLost("session-a", "手机号13800000000", "账号姓名", "138****0000");
        assertTrue(transcript(registerRequests.get(1)).contains("姓名张三"));
        assertTrue(transcript(registerRequests.get(1)).contains("请继续补充"));
        assertFalse(transcript(registerRequests.get(1)).contains("路由识别消息"));

        register.registerLost("session-b", "我丢了钥匙", "账号姓名", "138****0000");
        assertFalse(transcript(registerRequests.get(2)).contains("姓名张三"));
        assertFalse(transcript(registerRequests.get(2)).contains("13800000000"));

        memory.clear("session-a");
        register.registerLost("session-a", "清空后的新登记", "账号姓名", "138****0000");
        assertFalse(transcript(registerRequests.get(3)).contains("姓名张三"));
        register.registerLost("session-b", "给钥匙补充特征", "账号姓名", "138****0000");
        assertTrue(transcript(registerRequests.get(4)).contains("我丢了钥匙"));
    }

    @Test
    void foundRegistrationAndModificationShareMemoryWithoutMixingLostItems() {
        ConversationMemory memory = new ConversationMemory(new InMemoryChatMemoryStore());
        List<List<ChatMessage>> requests = new ArrayList<>();
        ChatModel model = mock(ChatModel.class);
        String json = "{\"output\":\"请继续补充拾物信息\",\"completed\":false}";
        when(model.chat(any(ChatRequest.class))).thenAnswer(call -> {
            requests.add(List.copyOf(call.getArgument(0, ChatRequest.class).messages()));
            return response(json);
        });
        LostPropertyAssistant assistant = AiServices.builder(LostPropertyAssistant.class)
                .chatModel(model).chatMemoryProvider(memory.provider(ConversationMemory.LOST_PROPERTY)).build();
        memory.provider(ConversationMemory.LOST_REGISTER).get("session-a")
                .add(dev.langchain4j.data.message.UserMessage.from("这是丢失登记的独立上下文"));

        assertEquals("请继续补充拾物信息", assistant.registerLostProperty("session-a", "捡到黑色钱包，拾得人李四", "账号姓名", "138****0000").getOutput());
        assistant.registerLostProperty("session-a", "想修改钱包特征为蓝色", "账号姓名", "138****0000");
        assertTrue(transcript(requests.get(1)).contains("拾得人李四"));
        assertTrue(transcript(requests.get(1)).contains("请继续补充拾物信息"));
        assertFalse(transcript(requests.get(1)).contains("丢失登记的独立上下文"));
        assistant.registerLostProperty("session-b", "捡到耳机", "账号姓名", "138****0000");
        assertFalse(transcript(requests.get(2)).contains("拾得人李四"));
        memory.clear("session-a");
        assistant.registerLostProperty("session-a", "重新开始", "账号姓名", "138****0000");
        assertFalse(transcript(requests.get(3)).contains("拾得人李四"));
    }

    private static ChatResponse response(String text) {
        return ChatResponse.builder().aiMessage(AiMessage.from(text)).build();
    }

    private static String transcript(List<ChatMessage> messages) {
        return messages.toString();
    }
}
