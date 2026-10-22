package com.lostsystem.service;

import com.lostsystem.entity.ChatConversationEntity;
import com.lostsystem.exception.BusinessException;
import com.lostsystem.mapper.ChatConversationMapper;
import com.lostsystem.mapper.SysUserMapper;
import com.lostsystem.vo.ChatConversationListItemVO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ChatConversationServiceTests {
    private ChatConversationMapper mapper;
    private ChatConversationService service;
    @BeforeEach void setup() { mapper=mock(ChatConversationMapper.class); service=new ChatConversationService(mock(SysUserMapper.class),mapper); }
    private ChatConversationEntity row(String mode) {
        var row=new ChatConversationEntity(); row.setId(3L); row.setUserId(7L); row.setSessionId("owned"); row.setMode(mode); return row;
    }
    @Test void createStoresOwnerAndModeAndReturnsServerId() {
        when(mapper.insert(any(ChatConversationEntity.class))).thenReturn(1);
        var result=service.create(7L,"found");
        assertEquals("found",result.mode()); assertNotNull(java.util.UUID.fromString(result.sessionId()));
        verify(mapper).insert(argThat((ChatConversationEntity row)->row.getUserId().equals(7L) && row.getMode().equals("found") && row.getSessionId().equals(result.sessionId())));
    }
    @Test void invalidRequestsDoNotTouchDatabase() {
        assertEquals(401,assertThrows(BusinessException.class,()->service.create(null,"lost")).getCode());
        assertEquals(400,assertThrows(BusinessException.class,()->service.create(7L,"other")).getCode());
        assertThrows(BusinessException.class,()->service.listMine(7L,0L,20));
        assertThrows(BusinessException.class,()->service.listMine(7L,null,101)); verifyNoInteractions(mapper);
    }
    @Test void listingUsesOwnerCursorAndOneExtraRow() {
        var first=new ChatConversationListItemVO(); first.setId(9L);
        var second=new ChatConversationListItemVO(); second.setId(8L);
        when(mapper.findByUserId(7L,10L,2)).thenReturn(List.of(first,second));
        var page=service.listMine(7L,10L,1);
        assertEquals(List.of(first),page.content()); assertTrue(page.hasMore()); assertEquals(9L,page.nextBeforeId());
    }
    @Test void emptyPageHasNoCursor() {
        when(mapper.findByUserId(7L,null,21)).thenReturn(List.of());
        var page=service.listMine(7L,null,20); assertFalse(page.hasMore()); assertNull(page.nextBeforeId());
    }
    @Test void backfillLocksOwnedConversationAndWritesOnlyNullMode() {
        when(mapper.findOwnedForUpdate("owned",7L)).thenReturn(row(null)); when(mapper.fillMissingMode(3L,7L,"search")).thenReturn(1);
        assertEquals("search",service.fillMissingMode(7L,"owned","search").mode());
        var order=inOrder(mapper); order.verify(mapper).findOwnedForUpdate("owned",7L); order.verify(mapper).fillMissingMode(3L,7L,"search");
    }
    @Test void identicalModeIsIdempotentWithoutWrite() {
        when(mapper.findOwnedForUpdate("owned",7L)).thenReturn(row("found"));
        assertEquals("found",service.fillMissingMode(7L,"owned","found").mode()); verify(mapper,never()).fillMissingMode(anyLong(),anyLong(),anyString());
    }
    @Test void differentExistingModeConflictsAndForeignSessionIsHidden() {
        when(mapper.findOwnedForUpdate("owned",7L)).thenReturn(row("found"));
        assertEquals(409,assertThrows(BusinessException.class,()->service.fillMissingMode(7L,"owned","lost")).getCode());
        assertEquals(404,assertThrows(BusinessException.class,()->service.fillMissingMode(8L,"owned","lost")).getCode());
        verify(mapper,never()).fillMissingMode(anyLong(),anyLong(),anyString());
    }
    @Test void failedConditionalWriteIsConflict() {
        when(mapper.findOwnedForUpdate("owned",7L)).thenReturn(row(null));
        assertEquals(409,assertThrows(BusinessException.class,()->service.fillMissingMode(7L,"owned","lost")).getCode());
    }
}
