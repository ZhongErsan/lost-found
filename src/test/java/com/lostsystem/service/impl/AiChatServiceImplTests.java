package com.lostsystem.service.impl;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.lostsystem.entity.ChatHistoryEntity;
import com.lostsystem.mapper.ChatHistoryMapper;
import com.lostsystem.chatmemory.ConversationMemory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class AiChatServiceImplTests {

    private AiChatServiceImpl service;
    private ChatHistoryMapper mapper;
    private ConversationMemory memory;

    @BeforeEach
    void setUp() {
        service = new AiChatServiceImpl();
        mapper = mock(ChatHistoryMapper.class);
        memory = mock(ConversationMemory.class);
        ReflectionTestUtils.setField(service, "chatHistoryMapper", mapper);
        ReflectionTestUtils.setField(service, "conversationMemory", memory);
    }

    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void queriesOnlyRequestedSessionWithStableSortingAndDatabasePagination() {
        ChatHistoryEntity entity = new ChatHistoryEntity();
        entity.setId(8L);
        entity.setSessionId("web-a");
        entity.setRole("1");
        entity.setContent("登记完成");
        when(mapper.selectCount(any())).thenReturn(25L);
        when(mapper.selectList(any(Wrapper.class))).thenReturn(List.of(entity));

        var page = service.queryChatHistory("web-a", PageRequest.of(1, 10, Sort.by("createdDate")));

        assertEquals(25, page.getTotalElements());
        assertEquals(3, page.getTotalPages());
        assertEquals("登记完成", page.getContent().get(0).content());
        ArgumentCaptor<QueryWrapper<ChatHistoryEntity>> query = ArgumentCaptor.forClass((Class) QueryWrapper.class);
        verify(mapper).selectList(query.capture());
        assertTrue(query.getValue().getSqlSegment().contains("session_id ="));
        assertTrue(query.getValue().getSqlSegment().contains("ORDER BY created_date ASC,id DESC"));
        assertTrue(query.getValue().getSqlSegment().contains("LIMIT 10 OFFSET 10"));
        assertTrue(query.getValue().getParamNameValuePairs().containsValue("web-a"));
        verify(mapper).selectCount(argThat(wrapper -> {
            QueryWrapper<?> count = (QueryWrapper<?>) wrapper;
            return count.getSqlSegment().contains("session_id =")
                    && count.getParamNameValuePairs().containsValue("web-a");
        }));
    }

    @Test
    void outOfRangePageKeepsTotalAndDoesNotReadRows() {
        when(mapper.selectCount(any())).thenReturn(2L);
        var page = service.queryChatHistory("web-a", PageRequest.of(3, 10));
        assertTrue(page.isEmpty());
        assertEquals(2, page.getTotalElements());
        verify(mapper, never()).selectList(any(Wrapper.class));
    }

    @Test
    void invalidSessionSizeAndSortAreRejectedBeforeQuerying() {
        assertThrows(ResponseStatusException.class, () -> service.queryChatHistory(" ", PageRequest.of(0, 10)));
        assertThrows(ResponseStatusException.class, () -> service.queryChatHistory("web-a", PageRequest.of(0, 101)));
        assertThrows(ResponseStatusException.class, () -> service.queryChatHistory("web-a",
                PageRequest.of(0, 10, Sort.by("id;DROP TABLE chat_history"))));
        verifyNoInteractions(mapper);
    }

    @Test
    void clearsOnlyRequestedSessionAndItsModelMemory() {
        service.clearChatHistory("web-a");
        verify(mapper).delete(argThat((Wrapper<ChatHistoryEntity> wrapper) -> {
            QueryWrapper<?> query = (QueryWrapper<?>) wrapper;
            return query.getSqlSegment().contains("session_id =")
                    && query.getParamNameValuePairs().containsValue("web-a");
        }));
        verify(memory).clear("web-a");
        verifyNoMoreInteractions(mapper, memory);
    }
}
