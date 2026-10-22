package com.lostsystem.controller;

import com.lostsystem.aiservice.AiAssistant;
import com.lostsystem.auth.CurrentUser;
import com.lostsystem.auth.UserContext;
import com.lostsystem.dto.ChatStreamEvent;
import com.lostsystem.exception.BusinessException;
import com.lostsystem.exception.GlobalExceptionHandler;
import com.lostsystem.service.AiChatService;
import com.lostsystem.service.ChatConversationService;
import com.lostsystem.utils.JsonUtils;
import com.lostsystem.vo.ChatHistoryVO;
import jakarta.servlet.AsyncEvent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableHandlerMethodArgumentResolver;
import org.springframework.mock.web.MockAsyncContext;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.request.async.AsyncRequestTimeoutException;
import reactor.core.publisher.Flux;
import reactor.test.publisher.TestPublisher;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class AiControllerTests {
    private AiChatService service;
    private AiAssistant assistant;
    private ChatConversationService conversations;
    private MockMvc mvc;
    private String textLine(String content) {
        return JsonUtils.toJson(ChatStreamEvent.text(content)) + "\n";
    }
    @BeforeEach
    void setUp() {
        service = mock(AiChatService.class);
        assistant = mock(AiAssistant.class);
        conversations = mock(ChatConversationService.class);
        AiController controller = new AiController();
        ReflectionTestUtils.setField(controller, "aiChatService", service);

        ReflectionTestUtils.setField(controller, "chatConversationService", conversations);
        mvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new GlobalExceptionHandler())
                .setCustomArgumentResolvers(new PageableHandlerMethodArgumentResolver()).build();
        UserContext.set(new CurrentUser(7L, "test-user", "测试用户", "USER", "login-session"));
    }

    @AfterEach
    void clearUser() { UserContext.clear(); }

    @Test
    void historyUsesExplicitOwnedSessionAndReturnsPagedJson() throws Exception {
        when(service.queryChatHistory(eq("owned-session"), any(Pageable.class))).thenAnswer(call ->
                new PageImpl<>(List.of(new ChatHistoryVO(9L, "owned-session", "1", "你好", null, null)),
                        call.getArgument(1, Pageable.class), 1));
        mvc.perform(get("/ai/chat-history").param("sessionId", "owned-session"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.content[0].sessionId").value("owned-session"))
                .andExpect(jsonPath("$.content[0].content").value("你好"))
                .andExpect(jsonPath("$.page.size").value(20));
        var order = inOrder(conversations, service);
        order.verify(conversations).requireOwned("owned-session", 7L);
        order.verify(service).queryChatHistory("owned-session", PageRequest.of(0, 20, Sort.by(Sort.Direction.DESC, "id")));
    }

    @Test
    void historyBindsPageSizeAndSort() throws Exception {
        when(service.queryChatHistory(eq("owned-session"), any(Pageable.class))).thenAnswer(call ->
                new PageImpl<>(List.of(), call.getArgument(1, Pageable.class), 0));
        mvc.perform(get("/ai/chat-history").param("sessionId", "owned-session")
                        .param("page", "2").param("size", "5").param("sort", "createdDate,asc"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.page.number").value(2));
        verify(service).queryChatHistory("owned-session", PageRequest.of(2, 5, Sort.by("createdDate")));
    }

    @Test
    void clearingChecksOwnerBeforeDeletingAndRequiresPost() throws Exception {
        mvc.perform(post("/ai/clear-chat-history/owned-session")).andExpect(status().isOk());
        var order = inOrder(conversations, service);
        order.verify(conversations).requireOwned("owned-session", 7L);
        order.verify(service).clearChatHistory("owned-session");
        mvc.perform(get("/ai/clear-chat-history/owned-session")).andExpect(status().isMethodNotAllowed());
    }

    @Test
    void allChatAndHistoryEntrypointsRejectAnotherUsersSessionBeforeWork() throws Exception {
        doThrow(new BusinessException(404, "聊天会话不存在或无权访问"))
                .when(conversations).requireOwned("other-session", 7L);
        for (String route : List.of("/ai/chat", "/ai/chat-stream", "/ai/chat-history")) {
            mvc.perform(route.endsWith("history") ? get(route).param("sessionId", "other-session")
                    : post(route).contentType("application/json").content("{\"sessionId\":\"other-session\",\"message\":\"查历史\"}"))
                    .andExpect(status().isNotFound());
        }
        mvc.perform(post("/ai/clear-chat-history/other-session")).andExpect(status().isNotFound());
        verifyNoInteractions(service, assistant);
    }

    @Test
    void missingLoginRejectsStreamBeforeOwnerCheck() throws Exception {
        UserContext.clear();
        mvc.perform(streamRequest()).andExpect(status().isUnauthorized());
        verifyNoInteractions(conversations, service);
    }

    @Test
    void missingSessionAndLegacyUserIdAreNotAccepted() throws Exception {
        mvc.perform(get("/ai/chat-history")).andExpect(status().isBadRequest());
        mvc.perform(post("/ai/chat-stream").contentType("application/json").content("{\"userId\":7,\"message\":\"消息\"}"))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(conversations, service);
    }

    @Test
    void legacyGetChatCannotSubmitMessages() throws Exception {
        mvc.perform(get("/ai/chat-stream").param("sessionId", "owned-session").param("message", "消息"))
                .andExpect(status().isMethodNotAllowed());
        verifyNoInteractions(conversations, service);
    }

    @Test
    void blankAndOverlongMessagesAreRejectedBeforeOwnerCheck() throws Exception {
        for (String message : List.of(" ", "字".repeat(1501))) {
            mvc.perform(post("/ai/chat-stream").contentType("application/json").content(new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(java.util.Map.of("sessionId","owned-session","message",message))))
                    .andExpect(status().isBadRequest());
        }
        verifyNoInteractions(conversations, service);
    }

    @Test
    void firstChunkIsWrittenBeforeCompletionAfterRequestThreadCleanup() throws Exception {
        TestPublisher<ChatStreamEvent> model = TestPublisher.create();
        when(service.chatStream("owned-session", "消息")).thenReturn(model.flux());
        MvcResult result = mvc.perform(streamRequest()).andExpect(request().asyncStarted()).andReturn();
        var order = inOrder(conversations, service);
        order.verify(conversations).requireOwned("owned-session", 7L);
        order.verify(service).chatStream("owned-session", "消息");
        UserContext.clear();
        model.next(ChatStreamEvent.text("找到"));
        assertEquals("application/x-ndjson;charset=utf-8", result.getResponse().getContentType());
        assertEquals(
                textLine("找到"),
                result.getResponse().getContentAsString(
                        java.nio.charset.StandardCharsets.UTF_8)
        );
        assertTrue(result.getResponse().isCommitted());
        model.assertSubscribers(1);
        model.next(ChatStreamEvent.text("钱包")).complete();
        result.getAsyncResult(5000);
        mvc.perform(asyncDispatch(result)).andExpect(status().isOk())
                .andExpect(content().contentType("application/x-ndjson;charset=utf-8"))
                .andExpect(content().bytes(
                        (textLine("找到") + textLine("钱包"))
                                .getBytes(java.nio.charset.StandardCharsets.UTF_8)));
    }

    @Test
    void timeoutCancelsUpstream() throws Exception {
        TestPublisher<ChatStreamEvent> model = TestPublisher.create();
        when(service.chatStream(anyString(), anyString())).thenReturn(model.flux());
        MvcResult result = mvc.perform(streamRequest()).andExpect(request().asyncStarted()).andReturn();
        MockAsyncContext context = (MockAsyncContext) result.getRequest().getAsyncContext();
        for (var listener : List.copyOf(context.getListeners())) listener.onTimeout(new AsyncEvent(context));
        model.assertCancelled();
        assertInstanceOf(AsyncRequestTimeoutException.class, result.getAsyncResult(5000));
        mvc.perform(asyncDispatch(result)).andExpect(status().isServiceUnavailable());
        assertEquals("", result.getResponse().getContentAsString());
    }

    @Test
    void disconnectCancelsUpstream() throws Exception {
        TestPublisher<ChatStreamEvent> model = TestPublisher.create();
        when(service.chatStream(anyString(), anyString())).thenReturn(model.flux());
        MvcResult result = mvc.perform(streamRequest()).andExpect(request().asyncStarted()).andReturn();
        MockAsyncContext context = (MockAsyncContext) result.getRequest().getAsyncContext();
        for (var listener : List.copyOf(context.getListeners())) {
            listener.onError(new AsyncEvent(context, new java.io.IOException("client disconnected")));
        }
        model.assertCancelled();
    }

    @Test
    void synchronousCompletionBeforeSubscribeReturnsDoesNotLoseChunks() throws Exception {
        when(service.chatStream(anyString(), anyString()))
                .thenReturn(Flux.just(
                        ChatStreamEvent.text("一"),
                        ChatStreamEvent.text("二")
                ));
        MvcResult result = mvc.perform(streamRequest()).andExpect(request().asyncStarted()).andReturn();
        result.getAsyncResult(5000);
        mvc.perform(asyncDispatch(result)).andExpect(status().isOk())
                .andExpect(content().contentType("application/x-ndjson;charset=utf-8"))
                .andExpect(content().bytes(
                        (textLine("一") + textLine("二"))
                                .getBytes(java.nio.charset.StandardCharsets.UTF_8)));
    }

    @Test
    void upstreamErrorBeforeFirstChunkReachesAsyncErrorHandler() throws Exception {
        BusinessException failure = new BusinessException(503, "模型暂不可用");
        when(service.chatStream(anyString(), anyString())).thenReturn(Flux.error(failure));
        MvcResult result = mvc.perform(streamRequest()).andExpect(request().asyncStarted()).andReturn();
        assertSame(failure, result.getAsyncResult(5000));
        mvc.perform(asyncDispatch(result)).andExpect(status().isServiceUnavailable())
                .andExpect(content().contentTypeCompatibleWith("application/json"))
                .andExpect(jsonPath("$.message").value("模型暂不可用"));
    }

    @Test
    void emptyStreamStillDeclaresUtf8PlainText() throws Exception {
        when(service.chatStream(anyString(), anyString())).thenReturn(Flux.empty());
        MvcResult result = mvc.perform(streamRequest()).andExpect(request().asyncStarted()).andReturn();
        result.getAsyncResult(5000);
        mvc.perform(asyncDispatch(result)).andExpect(status().isOk())
                .andExpect(content().contentType("application/x-ndjson;charset=utf-8"))
                .andExpect(content().string(""));
    }

    /** A partial response followed by a server timeout must not be reported as normal completion. */
    @Test
    void timeoutAfterPartialAnswerMustNotCompleteAsSuccessfulResponse() throws Exception {
        TestPublisher<ChatStreamEvent> model = TestPublisher.create();
        when(service.chatStream(anyString(), anyString())).thenReturn(model.flux());
        MvcResult result = mvc.perform(streamRequest()).andExpect(request().asyncStarted()).andReturn();
        model.next(ChatStreamEvent.text("这只是尚未完成的回答"));
        MockAsyncContext context = (MockAsyncContext) result.getRequest().getAsyncContext();
        for (var listener : List.copyOf(context.getListeners())) listener.onTimeout(new AsyncEvent(context));
        model.assertCancelled();
        assertInstanceOf(AsyncRequestTimeoutException.class, result.getAsyncResult(5000),
                "超时后调用 complete() 会把不完整回答作为正常 EOF 返回给客户端");
        // 已发送正文后无法更改 HTTP 200；必须保留异常完成状态，不能拼入错误 JSON。
        mvc.perform(asyncDispatch(result));
        assertEquals(
                textLine("这只是尚未完成的回答"),
                result.getResponse().getContentAsString(
                        java.nio.charset.StandardCharsets.UTF_8)
        );
        assertEquals("application/x-ndjson;charset=utf-8", result.getResponse().getContentType());
    }

    private org.springframework.test.web.servlet.request
            .MockHttpServletRequestBuilder streamRequest() {

        return post("/ai/chat-stream")
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "sessionId": "owned-session",
                      "message": "消息"
                    }
                    """);
    }
}
