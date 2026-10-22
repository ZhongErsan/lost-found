package com.lostsystem.controller;

import com.lostsystem.auth.CurrentUser;
import com.lostsystem.auth.UserContext;
import com.lostsystem.dto.ChatRequest;
import com.lostsystem.dto.ChatStreamEvent;
import com.lostsystem.exception.BusinessException;
import com.lostsystem.service.AiChatService;
import com.lostsystem.service.ChatConversationService;
import com.lostsystem.utils.JsonUtils;
import com.lostsystem.vo.ChatHistoryVO;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.data.web.PagedModel;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.context.request.async.AsyncRequestTimeoutException;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyEmitter;
import reactor.core.Disposable;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

@RestController
@RequestMapping("/ai")
public class AiController {

    @Autowired
    private AiChatService aiChatService;
    @Autowired
    private ChatConversationService chatConversationService;
    private static final MediaType TEXT_PLAIN_UTF8 = new MediaType("text", "plain", StandardCharsets.UTF_8);
    private static final MediaType NDJSON_UTF8 = new MediaType("application", "x-ndjson", StandardCharsets.UTF_8);
    @PostMapping(
            value = "/chat",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.TEXT_PLAIN_VALUE + ";charset=utf-8"
    )
    public String chat(@Valid @RequestBody ChatRequest request) {
        String sessionId = request.sessionId();
        String message = request.message();

        CurrentUser currentUser = UserContext.require();

        validateChatRequest(sessionId, message);

        chatConversationService.requireOwned(sessionId, currentUser.userId());

        return aiChatService.chatStream(sessionId, message)
                .filter(event -> "text".equals(event.type()))
                .map(ChatStreamEvent::content)
                .collectList()
                .map(parts -> String.join("", parts))
                .block(Duration.ofSeconds(120));
    }

    @PostMapping(
            value = "/chat-stream",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = "application/x-ndjson;charset=utf-8"
    )
    public ResponseEntity<ResponseBodyEmitter> chatStream(@Valid @RequestBody ChatRequest request) {
        String sessionId = request.sessionId();
        String message = request.message();
        // 身份和会话归属必须在请求入口完成校验。
        CurrentUser currentUser = UserContext.require();
        validateChatRequest(sessionId, message);
        chatConversationService.requireOwned(sessionId, currentUser.userId());

        ResponseBodyEmitter emitter = new ResponseBodyEmitter(120_000L);

        AtomicReference<Disposable> subscriptionRef = new AtomicReference<>();

        AtomicBoolean cancellationRequested = new AtomicBoolean(false);

        Runnable cancelSubscription = () -> {
            cancellationRequested.set(true);
            Disposable subscription = subscriptionRef.get();
            if (subscription != null) {
                subscription.dispose();
            }
        };
        // 客户端断开、请求完成或超时时，停止后续模型生成。
        emitter.onCompletion(cancelSubscription);
        emitter.onTimeout(() -> {
            cancelSubscription.run();
            // 超时表示响应未完成，不能把它作为正常 EOF 返回。
            emitter.completeWithError(new AsyncRequestTimeoutException());
        });
        emitter.onError(error -> cancelSubscription.run());
        // 订阅真正的流式结果。
        Disposable subscription = aiChatService
                .chatStream(sessionId, message)
                .subscribe(
                        chunk -> {
                            try {
                                // 每个片段都立即写入响应。
                                String line = JsonUtils.toJson(chunk) + "\n";
                                emitter.send(line, TEXT_PLAIN_UTF8);
                            } catch (IOException | IllegalStateException ex) {
                                // 写入失败时停止继续生成。
                                // IOException 对应的连接清理由 Servlet 容器处理。
                                cancelSubscription.run();
                            }
                        },
                        emitter::completeWithError,
                        emitter::complete
                );
        subscriptionRef.set(subscription);
        // 处理订阅句柄尚未赋值时就发生取消的边界情况。
        if (cancellationRequested.get()) {
            subscription.dispose();
        }
        return ResponseEntity.ok()
                .contentType(NDJSON_UTF8)
                .body(emitter);
    }

    @GetMapping("/chat-history")
    public PagedModel<ChatHistoryVO> queryChatHistory(
            @RequestParam("sessionId") String sessionId,
            @PageableDefault(size = 20, sort = "id", direction = Sort.Direction.DESC) Pageable page) {
        CurrentUser currentUser = UserContext.require();
        validateSessionId(sessionId);
        chatConversationService.requireOwned(sessionId, currentUser.userId());
        return new PagedModel<>(aiChatService.queryChatHistory(sessionId, page));
    }


    @PostMapping("/clear-chat-history/{sessionId}")
    public void clearChatHistory(@PathVariable("sessionId") String sessionId) {
        CurrentUser currentUser = UserContext.require();
        validateSessionId(sessionId);
        chatConversationService.requireOwned(sessionId, currentUser.userId());
        aiChatService.clearChatHistory(sessionId);
    }
    @GetMapping(value = "/embedding-index")
    public String embeddingIndex(){
        CurrentUser currentUser = UserContext.require();
        if (!currentUser.isAdmin()) {
            throw new BusinessException(403, "只有管理员可以重建索引");
        }
        return aiChatService.embeddingIndex();
    }
    @GetMapping(value = "/embedding-query")
    public List<String> embeddingQuery(String message){
        return aiChatService.embeddingQuery(message);
    }

    private void validateSessionId(String sessionId) {
        if (sessionId == null
                || sessionId.isBlank()
                || sessionId.length() > 64) {
            throw new BusinessException(400, "sessionId（聊天会话标识）不能为空");
        }
    }

    private void validateChatRequest(String sessionId, String message) {
        validateSessionId(sessionId);
        if (message == null
                || message.isBlank()
                || message.length() > 1500) {
            throw new BusinessException(400, "消息不能为空且不能超过 1500 个字符");
        }
    }

}
