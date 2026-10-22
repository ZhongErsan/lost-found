package com.lostsystem.controller;

import com.lostsystem.dto.LoginRequest;
import com.lostsystem.dto.RegisterRequest;
import com.lostsystem.service.AuthService;
import com.lostsystem.vo.CurrentUserVO;
import com.lostsystem.vo.LoginResponse;
import com.lostsystem.vo.RegisterResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;

    /**
     * 登录。
     */
    @PostMapping("/login")
    public LoginResponse login(@Valid @RequestBody LoginRequest request, HttpServletRequest servletRequest) {
        String clientIp = servletRequest.getRemoteAddr();
        return authService.login(request, clientIp);
    }

    /**
     * 注册普通用户。注册成功后需要调用登录接口获取 JWT。
     */
    @PostMapping("/register")
    @ResponseStatus(HttpStatus.CREATED)
    public RegisterResponse register(
            @Valid @RequestBody RegisterRequest request,
            HttpServletRequest servletRequest
    ) {
        return authService.register(request, servletRequest.getRemoteAddr());
    }
    /**
     * 退出登录。
     */
    @PostMapping("/logout")
    public void logout() {
        authService.logout();
    }

    /**
     * 查询当前登录用户。
     */
    @GetMapping("/me")
    public CurrentUserVO me() {
        return authService.currentUser();
    }
}
