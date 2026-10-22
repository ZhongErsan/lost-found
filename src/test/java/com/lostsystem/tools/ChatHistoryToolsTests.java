package com.lostsystem.tools;

import com.lostsystem.chatmemory.ConversationMemory;
import com.lostsystem.entity.ChatHistoryEntity;
import com.lostsystem.mapper.ChatHistoryMapper;
import com.lostsystem.aiservice.LostPropertyAssistant;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecifications;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.service.AiServices;
import dev.langchain4j.service.MemoryId;
import dev.langchain4j.service.UserMessage;
import dev.langchain4j.store.memory.chat.InMemoryChatMemoryStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class ChatHistoryToolsTests {
    @Test
    void latestRecordsArePresentedOldestFirstWithReadableRoles() {
        ChatHistoryMapper mapper = mock(ChatHistoryMapper.class);
        when(mapper.findTop20BySessionIdOrderByIdDesc("a"))
                .thenReturn(List.of(row("1", "请补充特征"), row("0", "我丢了钱包")));
        var history = new ChatHistoryTools(mapper).getChatHistory("a");
        assertEquals("我丢了钱包", history.get(0).getContent());
        assertEquals("user", history.get(0).getRole());
        assertEquals("请补充特征", history.get(1).getContent());
        assertEquals("assistant", history.get(1).getRole());
        verify(mapper).findTop20BySessionIdOrderByIdDesc("a");
        verifyNoMoreInteractions(mapper);
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"", " ", "\t"})
    void invalidMemoryIdsDoNotQueryDatabase(String sessionId) {
        ChatHistoryMapper mapper = mock(ChatHistoryMapper.class);
        assertTrue(new ChatHistoryTools(mapper).getChatHistory(sessionId).isEmpty());
        verifyNoInteractions(mapper);
    }

    @Test
    void overlongMemoryIdDoesNotQueryDatabase() {
        ChatHistoryMapper mapper = mock(ChatHistoryMapper.class);
        assertTrue(new ChatHistoryTools(mapper).getChatHistory("a".repeat(65)).isEmpty());
        verifyNoInteractions(mapper);
    }

    @Test
    void memoryIdIsNotExposedAsAModelSuppliedToolParameter() {
        var specification = ToolSpecifications.toolSpecificationsFrom(ChatHistoryTools.class).get(0);
        assertTrue(specification.parameters() == null || specification.parameters().properties().isEmpty());
    }

    @Test
    void realAiServiceInjectsOriginalMemoryIdEvenWhenModelSuppliesAnotherSession() {
        ChatHistoryMapper mapper = mock(ChatHistoryMapper.class);
        when(mapper.findTop20BySessionIdOrderByIdDesc("owned-session"))
                .thenReturn(List.of(row("0", "当前用户自己的历史")));
        ChatModel model = mock(ChatModel.class);
        var request = ToolExecutionRequest.builder().id("history-call").name("getChatHistory")
                .arguments("{\"sessionId\":\"another-users-session\"}").build();
        when(model.chat(any(ChatRequest.class))).thenReturn(
                ChatResponse.builder().aiMessage(AiMessage.from(request)).build(),
                ChatResponse.builder().aiMessage(AiMessage.from("已读取当前会话")).build());
        HistoryAssistant assistant = AiServices.builder(HistoryAssistant.class).chatModel(model)
                .chatMemoryProvider(new ConversationMemory(new InMemoryChatMemoryStore())
                        .provider(ConversationMemory.LOST_PROPERTY))
                .tools(new ChatHistoryTools(mapper)).build();
        assertEquals("已读取当前会话", assistant.chat("owned-session", "读取历史"));
        verify(mapper).findTop20BySessionIdOrderByIdDesc("owned-session");
        verifyNoMoreInteractions(mapper);
    }

    interface HistoryAssistant {
        String chat(@MemoryId String sessionId, @UserMessage String message);
    }

    @Test
    void streamingAssistantAlsoInjectsOriginalSessionIntoHistoryTool() {
        ChatHistoryMapper mapper = mock(ChatHistoryMapper.class);
        when(mapper.findTop20BySessionIdOrderByIdDesc("owned-stream"))
                .thenReturn(List.of(row("0", "当前会话历史")));
        StreamingChatModel model = mock(StreamingChatModel.class);
        var calls = new java.util.concurrent.atomic.AtomicInteger();
        doAnswer(call -> {
            StreamingChatResponseHandler handler = call.getArgument(1);
            if (calls.getAndIncrement() == 0) {
                var tool = ToolExecutionRequest.builder().id("stream-history").name("getChatHistory")
                        .arguments("{\"sessionId\":\"another-users-session\"}").build();
                handler.onCompleteResponse(ChatResponse.builder().aiMessage(AiMessage.from(tool)).build());
            } else {
                handler.onPartialResponse("已读取");
                handler.onPartialResponse("当前会话");
                handler.onCompleteResponse(ChatResponse.builder().aiMessage(AiMessage.from("已读取当前会话")).build());
            }
            return null;
        }).when(model).chat(any(ChatRequest.class), any(StreamingChatResponseHandler.class));
        LostPropertyAssistant assistant = AiServices.builder(LostPropertyAssistant.class)
                .streamingChatModel(model)
                .chatMemoryProvider(new ConversationMemory(new InMemoryChatMemoryStore())
                        .provider(ConversationMemory.LOST_PROPERTY))
                .tools(new ChatHistoryTools(mapper)).build();
        assertEquals("已读取当前会话", assistant.queryLostProperty("owned-stream", "查询钱包", "[]")
                .collectList().block(java.time.Duration.ofSeconds(5)).stream().collect(java.util.stream.Collectors.joining()));
        verify(mapper).findTop20BySessionIdOrderByIdDesc("owned-stream");
        verifyNoMoreInteractions(mapper);
    }

    private static ChatHistoryEntity row(String role, String content) {
        ChatHistoryEntity row = new ChatHistoryEntity();
        row.setRole(role);
        row.setContent(content);
        return row;
    }
}
