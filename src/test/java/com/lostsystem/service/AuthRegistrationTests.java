package com.lostsystem.service;

import com.lostsystem.auth.JwtService;
import com.lostsystem.auth.LoginProtectionService;
import com.lostsystem.auth.LoginSessionService;
import com.lostsystem.dto.RegisterRequest;
import com.lostsystem.dto.LoginRequest;
import com.lostsystem.entity.SysUser;
import com.lostsystem.exception.BusinessException;
import com.lostsystem.mapper.SysUserMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class AuthRegistrationTests {
    private SysUserMapper users;
    private LoginProtectionService protection;
    private JwtService jwt;
    private LoginSessionService sessions;
    private BCryptPasswordEncoder encoder;
    private AuthService service;

    @BeforeEach
    void setUp() {
        users = mock(SysUserMapper.class);
        protection = mock(LoginProtectionService.class);
        jwt = mock(JwtService.class);
        sessions = mock(LoginSessionService.class);
        encoder = new BCryptPasswordEncoder(4);
        service = new AuthService(users, encoder, jwt, sessions, protection);
    }

    private RegisterRequest request(String password) {
        return new RegisterRequest("new-user", password, " 张三 ", "138-0000-0000");
    }

    @Test
    void registersNormalUserWithHashAndDoesNotLogin() {
        when(users.insert(any(SysUser.class))).thenAnswer(call -> {
            SysUser user = call.getArgument(0);
            assertEquals("USER", user.getRole());
            assertEquals("ACTIVE", user.getStatus());
            assertEquals("张三", user.getRealName());
            assertEquals("13800000000", user.getPhone());
            assertNotEquals(" password-with-spaces ", user.getPassword());
            assertTrue(encoder.matches(" password-with-spaces ", user.getPassword()));
            assertFalse(encoder.matches("password-with-spaces", user.getPassword()));
            user.setId(7L);
            return 1;
        });
        var response = service.register(request(" password-with-spaces "), "127.0.0.1");
        assertEquals(7L, response.userId());
        assertEquals("new-user", response.username());
        assertEquals("USER", response.role());
        verify(protection).checkRequestRate("127.0.0.1");
        verifyNoInteractions(jwt, sessions);
    }

    @Test
    void duplicateUsernameDoesNotInsert() {
        when(users.findByUsername("new-user")).thenReturn(new SysUser());
        BusinessException error = assertThrows(BusinessException.class,
                () -> service.register(request("Password123"), "127.0.0.1"));
        assertEquals(409, error.getCode());
        verify(users, never()).insert(any(SysUser.class));
    }

    @Test
    void concurrentUniqueConstraintViolationReturnsConflict() {
        when(users.insert(any(SysUser.class))).thenThrow(new DuplicateKeyException("unique constraint"));
        BusinessException error = assertThrows(BusinessException.class,
                () -> service.register(request("Password123"), "127.0.0.1"));
        assertEquals(409, error.getCode());
        assertFalse(error.getMessage().contains("unique constraint"));
    }

    @Test
    void utf8PasswordOverBcryptLimitIsRejectedBeforeDatabase() {
        BusinessException error = assertThrows(BusinessException.class,
                () -> service.register(request("密".repeat(25)), "127.0.0.1"));
        assertEquals(400, error.getCode());
        verifyNoInteractions(users);
    }

    @Test
    void blankContactOrInvalidPhoneIsRejected() {
        for (RegisterRequest request : new RegisterRequest[]{
                new RegisterRequest("user-a", "Password123", " ", "13800000000"),
                new RegisterRequest("user-a", "Password123", "张三", "-------"),
                new RegisterRequest("user-a", "short", "张三", "13800000000")}) {
            assertEquals(400, assertThrows(BusinessException.class,
                    () -> service.register(request, "127.0.0.1")).getCode());
        }
        verifyNoInteractions(users);
    }

    @Test
    void rateLimitPreventsDatabaseWrite() {
        doThrow(new BusinessException(429, "请求过于频繁"))
                .when(protection).checkRequestRate("127.0.0.1");
        assertEquals(429, assertThrows(BusinessException.class,
                () -> service.register(request("Password123"), "127.0.0.1")).getCode());
        verifyNoInteractions(users, jwt, sessions);
    }

    @Test
    void insertFailureDoesNotReturnSuccess() {
        when(users.insert(any(SysUser.class))).thenReturn(0);
        assertEquals(500, assertThrows(BusinessException.class,
                () -> service.register(request("Password123"), "127.0.0.1")).getCode());
    }

    @Test
    void registeredPasswordWorksWithExistingLoginFlow() {
        SysUser[] storedUser = new SysUser[1];
        when(users.insert(any(SysUser.class))).thenAnswer(call -> {
            SysUser user = call.getArgument(0);
            user.setId(7L);
            storedUser[0] = user;
            return 1;
        });
        service.register(request("Password123"), "127.0.0.1");
        when(users.findByUsername("new-user")).thenReturn(storedUser[0]);
        when(jwt.generate(eq(storedUser[0]), anyString())).thenReturn("signed-token");

        var response = service.login(new LoginRequest("new-user", "Password123"), "127.0.0.1");
        assertEquals("signed-token", response.token());
        assertEquals(7L, response.userId());
        assertEquals("USER", response.role());
        verify(sessions).replaceSession(eq(7L), anyString());
    }
}
