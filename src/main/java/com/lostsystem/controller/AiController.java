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
    /**
     * 文本类型：text/plain;charset=UTF-8，用于ResponseBodyEmitter发送文本块
     */
    private static final MediaType TEXT_PLAIN_UTF8 = new MediaType("text", "plain", StandardCharsets.UTF_8);
    /**
     * NDJSON媒体类型：application/x-ndjson;charset=UTF-8
     * NDJSON：每行一条独立JSON，前端逐行解析实现流式输出
     */
    private static final MediaType NDJSON_UTF8 = new MediaType("application", "x-ndjson", StandardCharsets.UTF_8);
    /**
     * 【阻塞式完整对话接口】
     * 等待AI全部生成完成后，一次性返回拼接好的完整文本，非流式
     * @param request 聊天请求体，包含sessionId会话ID、message用户消息
     * @return AI拼接后的完整回答文本
     */
    @PostMapping(
            value = "/chat",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.TEXT_PLAIN_VALUE + ";charset=utf-8"
    )
    public String chat(@Valid @RequestBody ChatRequest request) {
        // 获取会话ID、用户输入消息
        String sessionId = request.sessionId();
        String message = request.message();

        CurrentUser currentUser = UserContext.require();
        // 参数合法性校验：sessionId、message长度、非空校验
        validateChatRequest(sessionId, message);
        // 权限校验：校验该session是否属于当前登录用户，防止越权访问别人会话
        chatConversationService.requireOwned(sessionId, currentUser.userId());
        // 调用流式底层接口，过滤出文本类型事件，把所有文本片段收集、拼接成完整字符串
        return aiChatService.chatStream(sessionId, message)
                .filter(event -> "text".equals(event.type()))
                .map(ChatStreamEvent::content)
                .collectList()
                .map(parts -> String.join("", parts))
                // 阻塞等待最多120秒，超时抛出异常
                .block(Duration.ofSeconds(120));
    }

    /**
     * 【NDJSON流式对话接口】
     * 使用ResponseBodyEmitter异步长连接，逐行返回ChatStreamEvent JSON，前端SSE/长轮询解析
     * 优势：打字机效果，实时输出模型返回内容，不会等全部生成完才返回
     * @param request 聊天请求体，sessionId会话ID、message用户消息
     * @return ResponseEntity包装ResponseBodyEmitter，异步流式输出
     */
    @PostMapping(
            value = "/chat-stream",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = "application/x-ndjson;charset=utf-8"
    )
    public ResponseEntity<ResponseBodyEmitter> chatStream(@Valid @RequestBody ChatRequest request) {
        String sessionId = request.sessionId();
        String message = request.message();
        // 入口校验登录态：必须在请求最开始校验身份，避免异步回调中上下文丢失（UserContext是ThreadLocal）
        CurrentUser currentUser = UserContext.require();
        // 参数校验
        validateChatRequest(sessionId, message);
        // 会话归属校验：防止越权
        chatConversationService.requireOwned(sessionId, currentUser.userId());
        // 构造异步响应发射器，超时时间120000ms=120s
        ResponseBodyEmitter emitter = new ResponseBodyEmitter(120_000L);
        // 原子引用：保存Reactor订阅句柄，用于取消订阅、停止模型生成
        AtomicReference<Disposable> subscriptionRef = new AtomicReference<>();
        // 原子标记：标记是否已经触发取消，处理竞态边界
        AtomicBoolean cancellationRequested = new AtomicBoolean(false);
        /**
         * 取消订阅逻辑：
         * 客户端断开、超时、异常时执行，dispose订阅，停止AI继续生成token，节约算力
         */
        Runnable cancelSubscription = () -> {
            cancellationRequested.set(true);
            Disposable subscription = subscriptionRef.get();
            if (subscription != null) {
                subscription.dispose();
            }
        };

        // 回调1：响应正常完成时，取消订阅
        emitter.onCompletion(cancelSubscription);
        // 回调2：异步请求超时，取消订阅，并标记为异常结束，避免前端当成正常EOF
        emitter.onTimeout(() -> {
            cancelSubscription.run();
            emitter.completeWithError(new AsyncRequestTimeoutException());
        });
        // 回调3：输出异常/连接断开，取消订阅
        emitter.onError(error -> cancelSubscription.run());

        // 订阅AI流式响应流，逐个下发chunk片段
        Disposable subscription = aiChatService
                .chatStream(sessionId, message)
                .subscribe(
                        // 每收到一块流数据：序列化为JSON + 换行符（NDJSON规范，一行一条json），写入发射器
                        chunk -> {
                            try {
                                String line = JsonUtils.toJson(chunk) + "\n";
                                emitter.send(line, TEXT_PLAIN_UTF8);
                            } catch (IOException | IllegalStateException ex) {
                                // IO异常：连接已经断开、管道关闭；不再继续推送，立刻取消订阅
                                cancelSubscription.run();
                            }
                        },
                        // 流异常：通知发射器异常结束
                        emitter::completeWithError,
                        // 流正常结束：通知发射器正常完成
                        emitter::complete
                );

        // 保存订阅句柄到原子引用，供取消逻辑使用
        subscriptionRef.set(subscription);

        // 边界防护：如果在订阅句柄赋值完成前，已经触发取消，则立刻释放订阅
        if (cancellationRequested.get()) {
            subscription.dispose();
        }

        // 返回异步响应体，ContentType标记为NDJSON
        return ResponseEntity.ok()
                .contentType(NDJSON_UTF8)
                .body(emitter);
    }

    /**
     * 分页查询单个会话的聊天历史记录
     * @param sessionId 会话唯一标识
     * @param page 分页参数，默认size=20，按id倒序
     * @return 分页包装的聊天记录VO
     */
    @GetMapping("/chat-history")
    public PagedModel<ChatHistoryVO> queryChatHistory(
            @RequestParam("sessionId") String sessionId,
            @PageableDefault(size = 20, sort = "id", direction = org.springframework.data.domain.Sort.Direction.DESC) Pageable page) {
        CurrentUser currentUser = UserContext.require();
        // sessionId参数校验
        validateSessionId(sessionId);
        // 权限校验：只能查自己的会话历史
        chatConversationService.requireOwned(sessionId, currentUser.userId());
        return new PagedModel<>(aiChatService.queryChatHistory(sessionId, page));
    }

    /**
     * 清空指定会话下全部聊天历史
     * @param sessionId 会话ID，路径变量
     */
    @PostMapping("/clear-chat-history/{sessionId}")
    public void clearChatHistory(@PathVariable("sessionId") String sessionId) {
        CurrentUser currentUser = UserContext.require();
        validateSessionId(sessionId);
        chatConversationService.requireOwned(sessionId, currentUser.userId());
        aiChatService.clearChatHistory(sessionId);
    }

    /**
     * 【管理员接口】重建向量知识库索引（Embedding索引）
     * 仅admin账号可调用，耗时较长，一般用于文档更新后刷新向量库
     * @return 重建结果信息
     */
    @GetMapping(value = "/embedding-index")
    public String embeddingIndex() {
        CurrentUser currentUser = UserContext.require();
        // 非管理员直接403禁止访问
        if (!currentUser.isAdmin()) {
            throw new BusinessException(403, "只有管理员可以重建索引");
        }
        return aiChatService.embeddingIndex();
    }

    /**
     * 向量检索接口：输入query，召回相似度最高的知识库文本片段
     * @param message 检索query文本
     * @return 召回的文本片段列表
     */
    @GetMapping(value = "/embedding-query")
    public List<String> embeddingQuery(String message) {
        return aiChatService.embeddingQuery(message);
    }

    /**
     * 校验sessionId参数：非空、非空白、长度上限64
     * @param sessionId 会话标识
     */
    private void validateSessionId(String sessionId) {
        if (sessionId == null || sessionId.isBlank() || sessionId.length() > 64) {
            throw new BusinessException(400, "sessionId（聊天会话标识）不能为空");
        }
    }

    /**
     * 校验聊天入参：sessionId + 用户消息message
     * message不能空白，最大1500字符，防止超长prompt攻击/压垮模型
     * @param sessionId 会话ID
     * @param message 用户输入消息
     */
    private void validateChatRequest(String sessionId, String message) {
        validateSessionId(sessionId);
        if (message == null || message.isBlank() || message.length() > 1500) {
            throw new BusinessException(400, "消息不能为空且不能超过 1500 个字符");
        }
    }
}