package com.lostsystem.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.lostsystem.aioutput.*;
import com.lostsystem.aiservice.*;
import com.lostsystem.auth.RegistrationContact;
import com.lostsystem.dto.ChatStreamEvent;
import com.lostsystem.entity.LostPropertyEntity;
import com.lostsystem.exception.BusinessException;
import com.lostsystem.mapper.LostPropertyMapper;
import com.lostsystem.service.ChatConversationService;
import com.lostsystem.service.LostPropertyIndexService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;
import java.time.Duration;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class LostPropertyRegistrationTests {
    private AiChatServiceImpl service;
    private LostPropertyAssistant assistant;
    private LostPropertyMapper mapper;
    private ChatConversationService conversations;
    private LostPropertyIndexService index;
    private LostPropertyOutput output;
    @BeforeEach
    void setUp() {
        service = new AiChatServiceImpl();
        assistant = mock(LostPropertyAssistant.class); mapper = mock(LostPropertyMapper.class);
        conversations = mock(ChatConversationService.class); index = mock(LostPropertyIndexService.class);
        when(conversations.requireOwnerUserId("owned")).thenReturn(7L);
        when(conversations.requireOwnerContact("owned")).thenReturn(new RegistrationContact(7L, "账号姓名", "13800000000"));
        var intentions = mock(AiIntentionAssistant.class);
        var intention = new IntentionOutput(); intention.setIntention(2);
        when(intentions.intention(eq("owned"), anyString())).thenReturn(intention);
        ReflectionTestUtils.setField(service, "aiIntentionAssistant", intentions);
        ReflectionTestUtils.setField(service, "lostPropertyAssistant", assistant);
        ReflectionTestUtils.setField(service, "lostPropertyMapper", mapper);
        ReflectionTestUtils.setField(service, "chatConversationService", conversations);
        ReflectionTestUtils.setField(service, "lostPropertyIndexService", index);
        output = new LostPropertyOutput(); output.setOutput("已处理"); output.setCompleted(true);
        output.setAction(RegistrationAction.CREATE); output.setContactConfirmed(true);
        output.setLostName("钱包"); output.setLostType("蓝色挂件");
        when(assistant.registerLostProperty(eq("owned"), anyString(), eq("账号姓名"), eq("138****0000"))).thenReturn(output);
        when(mapper.insert(any(LostPropertyEntity.class))).thenAnswer(call -> { call.<LostPropertyEntity>getArgument(0).setId(9L); return 1; });
        when(index.syncAfterCommit(9L)).thenReturn(true);
    }
    private String reply() {
        return service.chatStream("owned", "确认保存")
                .filter(event -> "text".equals(event.type()))
                .map(ChatStreamEvent::content)
                .collectList()
                .map(parts -> String.join("", parts))
                .block(Duration.ofSeconds(5));
    }
    private LostPropertyEntity existing() {
        var row = new LostPropertyEntity(); row.setId(9L); row.setUserId(7L); row.setStatus("AVAILABLE"); row.setVersion(3L);
        row.setUsername("原联系人"); row.setPhone("13900000000"); row.setLostName("钱包"); row.setLostType("黑色"); return row;
    }
    private void update() { output.setAction(RegistrationAction.UPDATE); output.setId(9L); when(mapper.selectOne(any())).thenReturn(existing()); }
    @Test void createsOwnedRecordUsingServerContactAndInitialState() {
        output.setUsername("伪造姓名"); output.setPhone("18800000000");
        assertEquals("已处理", reply());
        var saved = ArgumentCaptor.forClass(LostPropertyEntity.class); verify(mapper).insert(saved.capture());
        assertEquals(7L,saved.getValue().getUserId()); assertEquals("账号姓名",saved.getValue().getUsername());
        assertEquals("13800000000",saved.getValue().getPhone()); assertEquals("AVAILABLE",saved.getValue().getStatus());
        assertEquals(0L,saved.getValue().getVersion());
        verify(assistant).registerLostProperty("owned","确认保存","账号姓名","138****0000");
    }
    @Test void followUpReusesBusinessAssistantAndSession() {
        output.setCompleted(false); reply(); reply();
        verify(assistant,times(2)).registerLostProperty("owned","确认保存","账号姓名","138****0000"); verifyNoInteractions(mapper,index);
    }
    @Test void incompleteResponseNeverWrites() { output.setCompleted(null); assertEquals("已处理",reply()); verifyNoInteractions(mapper,index); }
    @Test void missingReplyNeverWrites() { output.setOutput(" "); assertThrows(IllegalStateException.class,this::reply); verifyNoInteractions(mapper,index); }
    @Test void newRegistrationRequiresContactConfirmation() { output.setContactConfirmed(false); assertThrows(BusinessException.class,this::reply); verifyNoInteractions(mapper,index); }
    @Test void missingAccountContactPreventsCreate() {
        when(conversations.requireOwnerContact("owned")).thenReturn(new RegistrationContact(7L,"账号姓名",null));
        when(assistant.registerLostProperty("owned","确认保存","账号姓名","")).thenReturn(output);
        assertThrows(BusinessException.class,this::reply); verifyNoInteractions(mapper,index);
    }
    @Test void createCannotCarryExistingId() { output.setId(9L); assertThrows(BusinessException.class,this::reply); verifyNoInteractions(mapper,index); }
    @Test void updateWithoutTargetNeverCreates() { output.setAction(RegistrationAction.UPDATE); assertThrows(BusinessException.class,this::reply); verifyNoInteractions(mapper,index); }
    @Test void updatePreservesContactAndUsesOwnerVersionAndItemFieldsOnly() {
        update(); output.setLostName(" "); output.setUsername("伪造姓名"); output.setPhone("18800000000");
        when(mapper.updateItemInformation(9L,7L,3L,"钱包","蓝色挂件")).thenReturn(1);
        assertEquals("已处理",reply()); verify(mapper).updateItemInformation(9L,7L,3L,"钱包","蓝色挂件");
        verify(mapper).selectOne(argThat(wrapper -> {
            var query = (QueryWrapper<?>)wrapper;
            return query.getSqlSegment().contains("user_id =") && query.getSqlSegment().contains("id =")
                && query.getParamNameValuePairs().containsValue(7L) && query.getParamNameValuePairs().containsValue(9L);
        })); verify(mapper,never()).insert(any(LostPropertyEntity.class));
    }
    @Test void missingOrForeignTargetDoesNotFallBackToCreate() {
        output.setAction(RegistrationAction.UPDATE); output.setId(9L);
        assertTrue(assertThrows(BusinessException.class,this::reply).getMessage().contains("无权修改"));
        verify(mapper,never()).insert(any(LostPropertyEntity.class)); verifyNoInteractions(index);
    }
    @Test void lockedItemCannotBeEdited() {
        update(); var row=existing(); row.setStatus("IN_HANDOVER"); when(mapper.selectOne(any())).thenReturn(row);
        assertThrows(BusinessException.class,this::reply); verify(mapper,never()).updateItemInformation(anyLong(),anyLong(),anyLong(),anyString(),anyString()); verifyNoInteractions(index);
    }
    @Test void optimisticConflictDoesNotReportSuccessOrSyncIndex() {
        update(); assertThrows(BusinessException.class,this::reply); verifyNoInteractions(index);
    }
    @Test void failedInsertDoesNotSyncIndex() {
        when(mapper.insert(any(LostPropertyEntity.class))).thenReturn(0); assertThrows(BusinessException.class,this::reply); verifyNoInteractions(index);
    }
}
