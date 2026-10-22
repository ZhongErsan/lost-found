package com.lostsystem.aop;

import com.lostsystem.dto.ChatStreamEvent;
import com.lostsystem.entity.ChatHistoryEntity;
import com.lostsystem.mapper.ChatHistoryMapper;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.annotation.Pointcut;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

@Component
@Slf4j
@Aspect
public class ChatFlowAop {

    private static final String USER_ROLE = "0";
    private static final String AI_ROLE = "1";

    @Autowired
    private ChatHistoryMapper chatHistoryMapper;

    @Pointcut("@annotation(com.lostsystem.aop.ChatFlow)")
    public void pointcut() {
    }

    @Around("pointcut()")
    public Object around(ProceedingJoinPoint joinPoint)
            throws Throwable {

        Object[] args = joinPoint.getArgs();

        if (args.length < 2
                || !(args[0] instanceof String sessionId)
                || !(args[1] instanceof String message)) {
            throw new IllegalStateException(
                    "@ChatFlow 方法必须以 sessionId、message 作为前两个参数"
            );
        }

        if (sessionId.isBlank() || message.isBlank()) {
            throw new IllegalArgumentException(
                    "sessionId 和 message 不能为空"
            );
        }

        // 只记录会话标识，不将用户消息全文写入应用日志。
        log.info("聊天请求开始，sessionId={}", sessionId);

        boolean streaming = Flux.class.isAssignableFrom(
                ((MethodSignature) joinPoint.getSignature()).getReturnType());
        // 同步方法在执行前保存；流式方法延迟到订阅时保存。
        if (!streaming) {
            saveChatHistory(sessionId, message, USER_ROLE);
        }

        Object result;

        try {
            result = joinPoint.proceed();
        } catch (Throwable ex) {
            log.warn(
                    "聊天方法执行失败，sessionId={}",
                    sessionId,
                    ex
            );
            throw ex;
        }

        // 流式结果：不能调用 result.toString() 保存。
        if (result instanceof Flux<?> flux) {
            return recordStreamingHistory(sessionId, message, flux);
        }

        // 兼容尚未改为流式的 String 返回方法。
        if (result instanceof String answer
                && !answer.isBlank()) {

            saveChatHistory(sessionId, answer, AI_ROLE);

            log.info(
                    "AI 回复保存成功，sessionId={}，字符数={}",
                    sessionId,
                    answer.length()
            );
        }

        return result;
    }

    private <T> Flux<T> recordStreamingHistory(String sessionId, String message, Flux<T> response) {
        return Flux.defer(() -> {
            // 每次订阅使用独立缓冲区，避免不同订阅的回答互相拼接。
            StringBuilder fullAnswer = new StringBuilder();
            Mono<Void> saveUser = Mono.fromRunnable(
                    () -> saveChatHistory(sessionId, message, USER_ROLE))
                    .subscribeOn(Schedulers.boundedElastic()).then();
            Mono<Void> saveAssistant = Mono.fromRunnable(() -> {
                String answer = fullAnswer.toString();
                if (!answer.isBlank()) {
                    saveChatHistory(sessionId, answer, AI_ROLE);
                    log.info("AI 回复保存成功，sessionId={}，字符数={}", sessionId, answer.length());
                }
            }).subscribeOn(Schedulers.boundedElastic()).then();

            // 用户消息保存成功后才订阅 AI；完整回复落库成功后才发送完成信号。
            // 异常或取消不会进入末尾的保存步骤，MyBatis 不占用模型回调线程。
            return saveUser.thenMany(
                    response.doOnNext(chunk -> {
                        if (chunk instanceof ChatStreamEvent event) {
                            if ("text".equals(event.type())
                                    && event.content() != null) {
                                fullAnswer.append(event.content());
                            }
                        } else if (chunk instanceof String text) {
                            fullAnswer.append(text);
                        }
                    })
            ).concatWith(saveAssistant.thenMany(Flux.<T>empty()));
        }).doOnError(ex -> log.warn("AI 流式回复失败，sessionId={}", sessionId, ex));
    }

    private void saveChatHistory(
            String sessionId,
            String content,
            String role
    ) {
        ChatHistoryEntity entity = new ChatHistoryEntity();

        entity.setSessionId(sessionId);
        entity.setRole(role);
        entity.setContent(content);

        int rows = chatHistoryMapper.insert(entity);

        if (rows != 1) {
            throw new IllegalStateException("聊天记录保存失败");
        }
    }
}
