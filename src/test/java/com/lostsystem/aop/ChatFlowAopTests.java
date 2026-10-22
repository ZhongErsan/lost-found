package com.lostsystem.aop;

import com.lostsystem.dto.ChatStreamEvent;
import com.lostsystem.entity.ChatHistoryEntity;
import com.lostsystem.mapper.ChatHistoryMapper;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.reflect.MethodSignature;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.aop.aspectj.annotation.AspectJProxyFactory;
import com.lostsystem.aioutput.IntentionOutput;
import com.lostsystem.aiservice.AiIntentionAssistant;
import com.lostsystem.service.AiChatService;
import com.lostsystem.service.impl.AiChatServiceImpl;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;
import reactor.test.publisher.TestPublisher;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class ChatFlowAopTests {

    @Test
    void streamingChunksAreForwardedAndOnlyCompleteAnswerIsSaved() throws Throwable {
        ChatHistoryMapper mapper = historyMapper();
        ChatFlowAop aspect = aspect(mapper);
        TestPublisher<String> model = TestPublisher.createCold();
        Flux<?> result = (Flux<?>) aspect.around(joinPoint(model.flux()));
        verifyNoInteractions(mapper);
        StepVerifier.create(result)
                .then(() -> model.next("找到"))
                .expectNextMatches("找到"::equals)
                .then(() -> verify(mapper, times(1)).insert(any(ChatHistoryEntity.class)))
                .then(() -> model.next("钱包").complete())
                .expectNextMatches("钱包"::equals)
                .expectComplete().verify(Duration.ofSeconds(5));
        ArgumentCaptor<ChatHistoryEntity> rows = ArgumentCaptor.forClass(ChatHistoryEntity.class);
        verify(mapper, times(2)).insert(rows.capture());
        assertEquals("0", rows.getAllValues().get(0).getRole());
        assertEquals("1", rows.getAllValues().get(1).getRole());
        assertEquals("找到钱包", rows.getAllValues().get(1).getContent());
    }

    @Test
    void failedOrCancelledStreamDoesNotSavePartialAnswer() throws Throwable {
        for (boolean cancelled : new boolean[]{false, true}) {
            ChatHistoryMapper mapper = historyMapper();
            TestPublisher<String> model = TestPublisher.createCold();
            Flux<?> result = (Flux<?>) aspect(mapper).around(joinPoint(model.flux()));
            var verification = StepVerifier.create(result)
                    .then(() -> model.next("部分回答"))
                    .expectNextMatches("部分回答"::equals);
            if (cancelled) {
                verification.thenCancel().verify(Duration.ofSeconds(5));
                model.assertCancelled();
            } else {
                verification.then(() -> model.error(new IllegalStateException("模型中断")))
                        .expectErrorMessage("模型中断").verify(Duration.ofSeconds(5));
            }
            verify(mapper, times(1)).insert(any(ChatHistoryEntity.class));
        }
    }

    @Test
    void emptyAnswerDoesNotCreateAssistantHistory() throws Throwable {
        ChatHistoryMapper mapper = historyMapper();
        Flux<?> result = (Flux<?>) aspect(mapper).around(joinPoint(Flux.just(" ", "\n")));
        StepVerifier.create(result).expectNextCount(2).expectComplete().verify(Duration.ofSeconds(5));
        verify(mapper, times(1)).insert(any(ChatHistoryEntity.class));
    }

    @Test
    void persistenceFailureBecomesStreamError() throws Throwable {
        ChatHistoryMapper mapper = historyMapper();
        when(mapper.insert(any(ChatHistoryEntity.class))).thenReturn(1, 0);
        Flux<?> result = (Flux<?>) aspect(mapper).around(joinPoint(Flux.just("完整回复")));
        StepVerifier.create(result).expectNextMatches("完整回复"::equals)
                .expectErrorMessage("聊天记录保存失败").verify(Duration.ofSeconds(5));
    }

    @Test
    void invalidArgumentsAreRejectedBeforeSavingOrCallingAi() throws Throwable {
        ChatHistoryMapper mapper = historyMapper();
        ProceedingJoinPoint point = mock(ProceedingJoinPoint.class);
        when(point.getArgs()).thenReturn(new Object[]{7L, "消息"});
        assertThrows(IllegalStateException.class, () -> aspect(mapper).around(point));
        verifyNoInteractions(mapper);
        verify(point, never()).proceed();
    }

    /** Regression: one HTTP subscription must produce exactly one user row and one assistant row. */
    @Test
    void realServiceThroughSpringAopMustPersistEachRoleExactlyOnce() {
        ChatHistoryMapper mapper = historyMapper();
        AiChatService service = proxiedService(mapper);
        StepVerifier.create(service.chatStream("web-a", "我的钱包丢了"))
                .expectNext(ChatStreamEvent.text("请提供物品特征"))
                .expectComplete()
                .verify(Duration.ofSeconds(5));
        ArgumentCaptor<ChatHistoryEntity> rows = ArgumentCaptor.forClass(ChatHistoryEntity.class);
        verify(mapper, times(2)).insert(rows.capture());
        assertAll(
                () -> assertEquals(1L, rows.getAllValues().stream().filter(row -> "0".equals(row.getRole())).count(),
                        "用户消息被 AOP 和 Service 重复保存"),
                () -> assertEquals(1L, rows.getAllValues().stream().filter(row -> "1".equals(row.getRole())).count(),
                        "AI 回复被 AOP 和 Service 重复保存"));
    }

    @Test
    void realServiceDoesNoDatabaseOrModelWorkBeforeSubscription() {
        ChatHistoryMapper mapper = historyMapper();
        AiIntentionAssistant intentions = mock(AiIntentionAssistant.class);
        AiChatService service = proxiedService(mapper, intentions);
        service.chatStream("web-a", "我的钱包丢了");
        verifyNoInteractions(mapper, intentions);
    }

    @Test
    void failedUserInsertPreventsModelInvocationThroughSpringAop() {
        ChatHistoryMapper mapper = historyMapper();
        when(mapper.insert(any(ChatHistoryEntity.class))).thenReturn(0);
        AiIntentionAssistant intentions = mock(AiIntentionAssistant.class);
        AiChatService service = proxiedService(mapper, intentions);
        StepVerifier.create(service.chatStream("web-a", "我的钱包丢了"))
                .expectErrorMessage("聊天记录保存失败").verify(Duration.ofSeconds(5));
        verifyNoInteractions(intentions);
        verify(mapper, times(1)).insert(any(ChatHistoryEntity.class));
    }

    @Test
    void completionWaitsForAssistantInsertAndDatabaseWritesUseBoundedElastic() throws Throwable {
        ChatHistoryMapper mapper = historyMapper();
        CountDownLatch insertStarted = new CountDownLatch(1);
        CountDownLatch allowInsert = new CountDownLatch(1);
        when(mapper.insert(any(ChatHistoryEntity.class))).thenAnswer(invocation -> {
            assertTrue(Thread.currentThread().getName().startsWith("boundedElastic-"));
            ChatHistoryEntity row = invocation.getArgument(0);
            if ("1".equals(row.getRole())) {
                insertStarted.countDown();
                assertTrue(allowInsert.await(5, TimeUnit.SECONDS));
            }
            return 1;
        });
        Flux<?> result = (Flux<?>) aspect(mapper).around(joinPoint(Flux.just("完整回复")));
        try {
            StepVerifier.create(result)
                    .expectNextMatches("完整回复"::equals)
                    .then(() -> assertTrue(assertDoesNotThrow(() -> insertStarted.await(5, TimeUnit.SECONDS))))
                    .expectNoEvent(Duration.ofMillis(100))
                    .then(allowInsert::countDown)
                    .expectComplete().verify(Duration.ofSeconds(5));
        } finally {
            allowInsert.countDown();
        }
    }

    @Test
    void eachSubscriptionUsesItsOwnAnswerBuffer() throws Throwable {
        ChatHistoryMapper mapper = historyMapper();
        Flux<?> result = (Flux<?>) aspect(mapper).around(joinPoint(Flux.just("找到", "钱包")));
        for (int i = 0; i < 2; i++) {
            StepVerifier.create(result).expectNextCount(2).expectComplete().verify(Duration.ofSeconds(5));
        }
        ArgumentCaptor<ChatHistoryEntity> rows = ArgumentCaptor.forClass(ChatHistoryEntity.class);
        verify(mapper, times(4)).insert(rows.capture());
        assertEquals("找到钱包", rows.getAllValues().get(1).getContent());
        assertEquals("找到钱包", rows.getAllValues().get(3).getContent());
    }

    private AiChatService proxiedService(ChatHistoryMapper mapper) {
        AiIntentionAssistant intentions = mock(AiIntentionAssistant.class);
        IntentionOutput output = new IntentionOutput();
        output.setIntention(0);
        output.setOutput("请提供物品特征");
        when(intentions.intention("web-a", "我的钱包丢了")).thenReturn(output);
        return proxiedService(mapper, intentions);
    }

    private AiChatService proxiedService(ChatHistoryMapper mapper, AiIntentionAssistant intentions) {
        AiChatServiceImpl target = new AiChatServiceImpl();
        ReflectionTestUtils.setField(target, "aiIntentionAssistant", intentions);
        ReflectionTestUtils.setField(target, "chatHistoryMapper", mapper);
        AspectJProxyFactory factory = new AspectJProxyFactory(target);
        factory.addAspect(aspect(mapper));
        return factory.getProxy();
    }

    private ChatHistoryMapper historyMapper() {
        ChatHistoryMapper mapper = mock(ChatHistoryMapper.class);
        when(mapper.insert(any(ChatHistoryEntity.class))).thenReturn(1);
        return mapper;
    }

    private ChatFlowAop aspect(ChatHistoryMapper mapper) {
        ChatFlowAop aspect = new ChatFlowAop();
        ReflectionTestUtils.setField(aspect, "chatHistoryMapper", mapper);
        return aspect;
    }

    private ProceedingJoinPoint joinPoint(Object result) throws Throwable {
        ProceedingJoinPoint point = mock(ProceedingJoinPoint.class);
        MethodSignature signature = mock(MethodSignature.class);
        when(signature.getReturnType()).thenReturn(result instanceof Flux<?> ? Flux.class : String.class);
        when(point.getSignature()).thenReturn(signature);
        when(point.getArgs()).thenReturn(new Object[]{"web-a", "我的钱包丢了"});
        when(point.proceed()).thenReturn(result);
        return point;
    }

    @Test
    void savesUserAndAssistantWithDifferentRolesInTheSameSession() throws Throwable {
        ChatHistoryMapper mapper = mock(ChatHistoryMapper.class);
        when(mapper.insert(any(ChatHistoryEntity.class))).thenReturn(1);
        ProceedingJoinPoint joinPoint = joinPoint("请提供物品特征");
        ChatFlowAop aspect = new ChatFlowAop();
        ReflectionTestUtils.setField(aspect, "chatHistoryMapper", mapper);

        assertEquals("请提供物品特征", aspect.around(joinPoint));

        ArgumentCaptor<ChatHistoryEntity> rows = ArgumentCaptor.forClass(ChatHistoryEntity.class);
        verify(mapper, times(2)).insert(rows.capture());
        assertEquals("0", rows.getAllValues().get(0).getRole());
        assertEquals("1", rows.getAllValues().get(1).getRole());
        assertEquals("web-a", rows.getAllValues().get(1).getSessionId());
        assertEquals("请提供物品特征", rows.getAllValues().get(1).getContent());
    }
    @Test
    void registrationEventIsForwardedButOnlyTextIsSaved() throws Throwable {
        ChatHistoryMapper mapper = historyMapper();
        ChatStreamEvent text = ChatStreamEvent.text("登记已保存");
        ChatStreamEvent saved =
                ChatStreamEvent.registrationSaved("lost", 9L, "CREATE");

        Flux<?> result = (Flux<?>) aspect(mapper).around(
                joinPoint(Flux.just(text, saved))
        );

        StepVerifier.create(result)
                .expectNextMatches(text::equals)
                .expectNextMatches(saved::equals)
                .expectComplete()
                .verify(Duration.ofSeconds(5));

        ArgumentCaptor<ChatHistoryEntity> rows =
                ArgumentCaptor.forClass(ChatHistoryEntity.class);
        verify(mapper, times(2)).insert(rows.capture());

        assertEquals("0", rows.getAllValues().get(0).getRole());
        assertEquals("1", rows.getAllValues().get(1).getRole());
        assertEquals("登记已保存", rows.getAllValues().get(1).getContent());
    }
}
