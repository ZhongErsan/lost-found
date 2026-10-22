package com.lostsystem.service;

import com.lostsystem.auth.*;
import com.lostsystem.dto.LoginRequest;
import com.lostsystem.dto.RegisterRequest;
import com.lostsystem.entity.SysUser;
import com.lostsystem.exception.BusinessException;
import com.lostsystem.mapper.SysUserMapper;
import com.lostsystem.vo.CurrentUserVO;
import com.lostsystem.vo.LoginResponse;
import com.lostsystem.vo.RegisterResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.util.UUID;
import java.nio.charset.StandardCharsets;

@Service
@RequiredArgsConstructor
public class AuthService {

    private final SysUserMapper sysUserMapper;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    private final LoginSessionService loginSessionService;
    private final LoginProtectionService loginProtectionService;

    /**
     * 注册普通用户。注册不会自动创建登录会话，用户需要随后登录。
     */
    public RegisterResponse register(RegisterRequest request, String clientIp) {
        // 复用已有 Redis IP 限流，避免匿名注册绕过认证接口的请求限制。
        loginProtectionService.checkRequestRate(clientIp);

        if (request == null) {
            throw new BusinessException(400, "注册信息不能为空");
        }

        String username = request.username() == null ? "" : request.username().trim();
        String realName = request.realName() == null ? "" : request.realName().trim();
        String phone = request.phone() == null ? "" : request.phone().replaceAll("[ ()-]", "");

        if (username.isEmpty() || realName.isEmpty() || phone.isEmpty()) {
            throw new BusinessException(400, "注册信息不能为空");
        }

        if (!username.matches("[A-Za-z0-9._-]{3,32}") || realName.length() > 50) {
            throw new BusinessException(400, "用户名或姓名格式不正确");
        }
        if (!phone.matches("\\+?[0-9]{7,15}")) {
            throw new BusinessException(400, "手机号格式不正确");
        }
        // BCrypt 上限是 UTF-8 字节数，而不是 Java 字符数；密码不能 trim。
        String password = request.password();
        if (password == null || password.isBlank() || password.length() < 8
                || password.getBytes(StandardCharsets.UTF_8).length > 72) {
            throw new BusinessException(400, "密码至少 8 个字符且不能超过 72 个 UTF-8 字节");
        }

        if (sysUserMapper.findByUsername(username) != null) {
            throw new BusinessException(409, "用户名已存在");
        }

        SysUser user = new SysUser();
        user.setUsername(username);
        user.setPassword(passwordEncoder.encode(password));
        user.setRealName(realName);
        user.setPhone(phone);
        user.setRole("USER");
        user.setStatus("ACTIVE");

        try {
            int rows = sysUserMapper.insert(user);
            if (rows != 1 || user.getId() == null) {
                throw new BusinessException(500, "注册失败，请稍后重试");
            }
        } catch (DuplicateKeyException exception) {
            // 预检查不能替代数据库唯一索引，并发注册时以数据库结果为准。
            throw new BusinessException(409, "用户名或联系方式已被使用", exception);
        }

        return new RegisterResponse(
                user.getId(),
                user.getUsername(),
                user.getRealName(),
                user.getPhone(),
                user.getRole()
        );
    }
    /**
     * 登录。
     */
    public LoginResponse login(LoginRequest request, String clientIp) {
        // 先限制请求频率，再进行数据库查询和密码校验。
        loginProtectionService.checkRequestRate(clientIp);

        String username = request.username().trim();

        // 已经被临时锁定的账号，直接拒绝本次登录。
        loginProtectionService.checkPasswordLock(username);

        SysUser user = sysUserMapper.findByUsername(username);

        boolean passwordMatches = user != null
                && user.getPassword() != null
                && passwordEncoder.matches(
                request.password(),
                user.getPassword()
        );

        if (!passwordMatches) {
            /*
             * 用户不存在也累计错误次数，
             * 并保持统一的用户名或密码错误提示。
             *
             * 如果这次错误触发锁定，
             * recordPasswordFailure 会直接抛出 429。
             */
            loginProtectionService.recordPasswordFailure(username);

            throw new BusinessException(
                    401,
                    "用户名或密码错误"
            );
        }

        if (!"ACTIVE".equalsIgnoreCase(user.getStatus())) {
            throw new BusinessException(
                    403,
                    "账号已被禁用"
            );
        }

        String loginSessionId = UUID.randomUUID().toString();

        /*
         * 先生成 Token，再更新 Redis 登录会话。
         * 避免 Token 生成失败时，提前覆盖用户原来的有效会话。
         */
        String token = jwtService.generate(user, loginSessionId);

        loginProtectionService.clearPasswordFailures(username);

        loginSessionService.replaceSession(user.getId(), loginSessionId);

        return new LoginResponse(
                token,
                user.getId(),
                user.getUsername(),
                user.getRealName(),
                user.getRole()
        );
    }
    /**
     * 退出登录。
     */
    public void logout() {
        CurrentUser currentUser = UserContext.require();

        loginSessionService.removeSessionIfMatch(
                currentUser.userId(),
                currentUser.loginSessionId()
        );
    }

    /**
     * 查询当前登录用户。
     */
    public CurrentUserVO currentUser() {
        return CurrentUserVO.from(
                UserContext.require()
        );
    }
}
