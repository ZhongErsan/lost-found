package com.lostsystem.controller;

import com.lostsystem.auth.CurrentUser;
import com.lostsystem.auth.UserContext;
import com.lostsystem.exception.BusinessException;
import com.lostsystem.exception.GlobalExceptionHandler;
import com.lostsystem.service.ChatConversationService;
import com.lostsystem.vo.ChatConversationPageVO;
import com.lostsystem.vo.ChatConversationVO;
import org.junit.jupiter.api.*;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import java.util.List;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class ChatConversationControllerTests {
    private ChatConversationService service;
    private MockMvc mvc;
    @BeforeEach void setup() {
        service=mock(ChatConversationService.class);
        mvc=MockMvcBuilders.standaloneSetup(new ChatConversationController(service)).setControllerAdvice(new GlobalExceptionHandler()).build();
        UserContext.set(new CurrentUser(7L,"test","用户","USER","login-session"));
    }
    @AfterEach void cleanup() { UserContext.clear(); }
    @Test void createUsesAuthenticatedIdAndJsonMode() throws Exception {
        when(service.create(7L,"search")).thenReturn(new ChatConversationVO("owned","search"));
        mvc.perform(post("/api/conversations").contentType("application/json").content("{\"mode\":\"search\",\"userId\":99}"))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.sessionId").value("owned")).andExpect(jsonPath("$.mode").value("search"));
        verify(service).create(7L,"search");
    }
    @Test void missingAndInvalidModeRejectedBeforeService() throws Exception {
        for (String body:List.of("{}","{\"mode\":\"other\"}","{\"mode\":null}")) {
            mvc.perform(post("/api/conversations").contentType("application/json").content(body)).andExpect(status().isBadRequest());
            mvc.perform(put("/api/conversations/owned/mode").contentType("application/json").content(body)).andExpect(status().isBadRequest());
        }
        verifyNoInteractions(service);
    }
    @Test void listUsesOwnerCursorAndNoStore() throws Exception {
        when(service.listMine(7L,10L,5)).thenReturn(new ChatConversationPageVO(List.of(),false,null));
        mvc.perform(get("/api/conversations").param("size","5").param("beforeId","10"))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control","no-store")).andExpect(jsonPath("$.hasMore").value(false));
        verify(service).listMine(7L,10L,5);
    }
    @Test void backfillUsesOwnerAndReturnsConflictAsJson() throws Exception {
        when(service.fillMissingMode(7L,"owned","found")).thenReturn(new ChatConversationVO("owned","found"));
        mvc.perform(put("/api/conversations/owned/mode").contentType("application/json").content("{\"mode\":\"found\"}"))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control","no-store"));
        when(service.fillMissingMode(7L,"owned","lost")).thenThrow(new BusinessException(409,"已设置模式"));
        mvc.perform(put("/api/conversations/owned/mode").contentType("application/json").content("{\"mode\":\"lost\"}"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.message").value("已设置模式"));
    }
    @Test void noLoginCannotCreateListOrBackfill() throws Exception {
        UserContext.clear();
        mvc.perform(get("/api/conversations")).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/conversations").contentType("application/json").content("{\"mode\":\"lost\"}")).andExpect(status().isUnauthorized());
        mvc.perform(put("/api/conversations/owned/mode").contentType("application/json").content("{\"mode\":\"lost\"}")).andExpect(status().isUnauthorized());
        verifyNoInteractions(service);
    }
}
