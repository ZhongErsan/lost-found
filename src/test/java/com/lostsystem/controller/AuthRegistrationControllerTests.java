package com.lostsystem.controller;

import com.lostsystem.auth.AuthInterceptor;
import com.lostsystem.auth.JwtService;
import com.lostsystem.auth.LoginSessionService;
import com.lostsystem.auth.UserContext;
import com.lostsystem.config.WebMvcConfig;
import com.lostsystem.dto.RegisterRequest;
import com.lostsystem.exception.GlobalExceptionHandler;
import com.lostsystem.exception.BusinessException;
import com.lostsystem.mapper.SysUserMapper;
import com.lostsystem.service.AuthService;
import com.lostsystem.vo.RegisterResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockServletContext;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** 使用真实 MVC 拦截器注册配置，验证匿名注册与受保护路由。 */
class AuthRegistrationControllerTests {
    private AnnotationConfigWebApplicationContext context;
    private AuthService service;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        context = new AnnotationConfigWebApplicationContext();
        context.setServletContext(new MockServletContext());
        context.register(TestConfig.class);
        context.refresh();
        service = context.getBean(AuthService.class);
        mvc = MockMvcBuilders.webAppContextSetup(context).build();
    }

    @AfterEach
    void tearDown() {
        UserContext.clear();
        context.close();
    }

    private String body() {
        return """
                {"username":"new-user","password":"Password123",
                 "realName":"张三","phone":"13800000000",
                 "role":"ADMIN","status":"DISABLED","userId":999}
                """;
    }

    @Test
    void anonymousRegistrationReturnsCreatedAndNoCredentials() throws Exception {
        when(service.register(any(RegisterRequest.class), anyString()))
                .thenReturn(new RegisterResponse(7L, "new-user", "张三", "13800000000", "USER"));
        mvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON).content(body()))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.userId").value(7))
                .andExpect(jsonPath("$.role").value("USER"))
                .andExpect(jsonPath("$.password").doesNotExist())
                .andExpect(jsonPath("$.token").doesNotExist());
        verify(service).register(eq(new RegisterRequest("new-user", "Password123", "张三", "13800000000")), anyString());
    }

    @Test
    void invalidRequestsAreRejectedBeforeService() throws Exception {
        for (String body : new String[]{"{}", body().replace("Password123", "short"),
                body().replace("13800000000", "invalid"), body().replace("new-user", "bad username")}) {
            mvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isBadRequest()).andExpect(jsonPath("$.message").exists());
        }
        verifyNoInteractions(service);
    }

    @Test
    void meAndLogoutStillRequireAuthentication() throws Exception {
        mvc.perform(get("/api/auth/me")).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/auth/logout")).andExpect(status().isUnauthorized());
        verifyNoInteractions(service);
    }

    @Test
    void registrationOnlySupportsPost() throws Exception {
        mvc.perform(get("/api/auth/register")).andExpect(status().isMethodNotAllowed());
        verifyNoInteractions(service);
    }

    @Test
    void duplicateRegistrationUsesExistingJsonExceptionHandler() throws Exception {
        when(service.register(any(RegisterRequest.class), anyString()))
                .thenThrow(new BusinessException(409, "用户名已存在"));
        mvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON).content(body()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value(409))
                .andExpect(jsonPath("$.message").value("用户名已存在"));
    }

    @Configuration
    @EnableWebMvc
    @Import({AuthController.class, WebMvcConfig.class, GlobalExceptionHandler.class})
    static class TestConfig {
        @Bean
        AuthService authService() { return mock(AuthService.class); }

        @Bean
        AuthInterceptor authInterceptor() {
            return new AuthInterceptor(mock(JwtService.class), mock(LoginSessionService.class), mock(SysUserMapper.class));
        }
    }
}
