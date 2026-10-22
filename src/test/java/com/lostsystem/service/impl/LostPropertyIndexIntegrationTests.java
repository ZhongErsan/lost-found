package com.lostsystem.service.impl;

import com.lostsystem.aioutput.IntentionOutput;
import com.lostsystem.aioutput.LostPropertyOutput;
import com.lostsystem.aioutput.RegistrationAction;
import com.lostsystem.aiservice.AiIntentionAssistant;
import com.lostsystem.aiservice.LostPropertyAssistant;
import com.lostsystem.auth.RegistrationContact;
import com.lostsystem.dto.ChatStreamEvent;
import com.lostsystem.entity.LostPropertyEntity;
import com.lostsystem.exception.BusinessException;
import com.lostsystem.mapper.LostPropertyMapper;
import com.lostsystem.service.ChatConversationService;
import com.lostsystem.service.LostPropertyIndexService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class LostPropertyIndexIntegrationTests {
    private AiChatServiceImpl service;
    private LostPropertyMapper mapper;
    private LostPropertyIndexService index;
    private LostPropertyOutput output;

    @BeforeEach
    void setUp() {
        service = new AiChatServiceImpl();
        mapper = mock(LostPropertyMapper.class);
        index = mock(LostPropertyIndexService.class);
        var intentions = mock(AiIntentionAssistant.class);
        var intention = new IntentionOutput();
        intention.setIntention(2);
        when(intentions.intention(anyString(), anyString())).thenReturn(intention);
        var conversation = mock(ChatConversationService.class);
        when(conversation.requireOwnerUserId("owned")).thenReturn(22L);
        when(conversation.requireOwnerContact("owned"))
                .thenReturn(new RegistrationContact(22L, "账号姓名", "13800000000"));
        var assistant = mock(LostPropertyAssistant.class);
        output = new LostPropertyOutput();
        output.setCompleted(true);
        output.setContactConfirmed(true);
        output.setAction(RegistrationAction.CREATE);
        output.setLostName("钱包");
        output.setLostType("蓝色挂件");
        output.setOutput("已保存");
        when(assistant.registerLostProperty(anyString(), anyString(), anyString(), anyString())).thenReturn(output);
        ReflectionTestUtils.setField(service, "aiIntentionAssistant", intentions);
        ReflectionTestUtils.setField(service, "lostPropertyAssistant", assistant);
        ReflectionTestUtils.setField(service, "lostPropertyMapper", mapper);
        ReflectionTestUtils.setField(service, "lostPropertyIndexService", index);
        ReflectionTestUtils.setField(service, "chatConversationService", conversation);
        when(mapper.insert(any(LostPropertyEntity.class))).thenAnswer(call -> {
            call.<LostPropertyEntity>getArgument(0).setId(7L);
            return 1;
        });
        when(index.syncAfterCommit(7L)).thenReturn(true);
    }

    private String reply() {
        return service.chatStream("owned", "登记拾到的钱包")
                .filter(event -> "text".equals(event.type()))
                .map(ChatStreamEvent::content)
                .collectList()
                .map(parts -> String.join("", parts))
                .block(Duration.ofSeconds(5));
    }

    @Test
    void insertSyncsGeneratedDatabaseIdAfterSave() {
        assertEquals("已保存", reply());
        var order = inOrder(mapper, index);
        order.verify(mapper).insert(any(LostPropertyEntity.class));
        order.verify(index).syncAfterCommit(7L);
    }

    @Test
    void updateSyncsOwnedRecordAfterSave() {
        output.setAction(RegistrationAction.UPDATE);
        output.setId(7L);
        var row = new LostPropertyEntity();
        row.setId(7L);
        row.setUserId(22L);
        row.setStatus("AVAILABLE");
        row.setVersion(0L);
        when(mapper.selectOne(any())).thenReturn(row);
        when(mapper.updateItemInformation(7L, 22L, 0L, "钱包", "蓝色挂件")).thenReturn(1);
        assertEquals("已保存", reply());
        var order = inOrder(mapper, index);
        order.verify(mapper).updateItemInformation(7L, 22L, 0L, "钱包", "蓝色挂件");
        order.verify(index).syncAfterCommit(7L);
    }

    @Test
    void failedDatabaseWriteDoesNotTouchIndex() {
        when(mapper.insert(any(LostPropertyEntity.class))).thenReturn(0);
        assertThrows(BusinessException.class, this::reply);
        verifyNoInteractions(index);
    }

    @Test
    void incompleteRegistrationDoesNotTouchIndex() {
        output.setCompleted(false);
        assertEquals("已保存", reply());
        verifyNoInteractions(mapper, index);
    }

    @Test
    void indexFailureDoesNotLoseSuccessfulRegistration() {
        when(index.syncAfterCommit(7L)).thenReturn(false);
        assertTrue(reply().contains("检索索引正在同步"));
        verify(mapper, times(1)).insert(any(LostPropertyEntity.class));
    }
}
