package com.lostsystem.controller;

import com.lostsystem.auth.CurrentUser;
import com.lostsystem.auth.UserContext;
import com.lostsystem.dto.CreateClaimRequest;
import com.lostsystem.exception.GlobalExceptionHandler;
import com.lostsystem.service.ItemClaimService;
import com.lostsystem.vo.ClaimContactVO;
import com.lostsystem.vo.ClaimDetailVO;
import com.lostsystem.vo.ClaimSubmissionVO;
import org.junit.jupiter.api.*;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class ItemClaimControllerTests {
    private ItemClaimService service;
    private MockMvc mvc;
    @BeforeEach void setup() {
        service=mock(ItemClaimService.class);
        mvc=MockMvcBuilders.standaloneSetup(new ItemClaimController(service)).setControllerAdvice(new GlobalExceptionHandler()).build();
        UserContext.set(new CurrentUser(7L,"test","用户","USER","login-session"));
    }
    @AfterEach void cleanup() { UserContext.clear(); }
    @Test void creationAndActionsUseCurrentUser() throws Exception {
        mvc.perform(post("/api/claims").contentType("application/json").content("{\"lostPropertyId\":8,\"lostRegisterId\":9,\"verificationText\":\"proof\",\"userId\":99}"))
                .andExpect(status().isOk());
        verify(service).create(7L,new CreateClaimRequest(8L,9L,"proof"));
        for(String action:java.util.List.of("approve","handover","receive","cancel")) mvc.perform(post("/api/claims/21/"+action)).andExpect(status().isOk());
        verify(service).approve(7L,21L); verify(service).handover(7L,21L); verify(service).receive(7L,21L); verify(service).cancel(7L,21L);
    }
    @Test void invalidCreationAndRejectionNeverReachService() throws Exception {
        mvc.perform(post("/api/claims").contentType("application/json").content("{\"lostPropertyId\":0,\"lostRegisterId\":9,\"verificationText\":\"proof\"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/claims/21/reject").contentType("application/json").content("{\"rejectReason\":\" \"}"))
                .andExpect(status().isBadRequest()); verifyNoInteractions(service);
    }
    @Test void detailAndContactAreNotCached() throws Exception {
        when(service.detail(7L,21L)).thenReturn(new ClaimDetailVO());
        when(service.contact(7L,21L)).thenReturn(new ClaimContactVO());
        mvc.perform(get("/api/claims/21")).andExpect(status().isOk()).andExpect(header().string("Cache-Control","no-store"));
        mvc.perform(get("/api/claims/21/contact")).andExpect(status().isOk()).andExpect(header().string("Cache-Control","no-store"));
    }
    @Test void noLoginCannotAct() throws Exception {
        UserContext.clear(); mvc.perform(post("/api/claims/21/receive")).andExpect(status().isUnauthorized()); verifyNoInteractions(service);
    }
}
