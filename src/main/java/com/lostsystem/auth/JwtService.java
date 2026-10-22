package com.lostsystem.auth;

import com.lostsystem.entity.SysUser;
import com.lostsystem.exception.BusinessException;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;

/**
 * JWT 生成和解析服务。
 */
@Service
public class JwtService {

    private final SecretKey secretKey;
    private final long expirationSeconds;

    public JwtService(
            @Value("${app.jwt.secret}") String secret,
            @Value("${app.jwt.expiration-seconds:7200}") long expirationSeconds
    ) {
        if (secret == null || secret.isBlank()) {
            throw new IllegalStateException("app.jwt.secret 不能为空");
        }

        if (secret.getBytes(StandardCharsets.UTF_8).length < 32) {
            throw new IllegalStateException(
                    "app.jwt.secret 至少需要 32 个字节"
            );
        }

        if (expirationSeconds <= 0) {
            throw new IllegalStateException(
                    "app.jwt.expiration-seconds 必须大于 0"
            );
        }

        this.secretKey = Keys.hmacShaKeyFor(
                secret.getBytes(StandardCharsets.UTF_8)
        );
        this.expirationSeconds = expirationSeconds;
    }

    /**
     * 登录成功后生成 JWT。
     *
     * sub：用户 ID
     * sid：本次登录会话 ID
     * role：角色信息
     */
    public String generate(
            SysUser user,
            String loginSessionId
    ) {
        if (user == null || user.getId() == null) {
            throw new IllegalArgumentException("用户信息不能为空");
        }

        if (loginSessionId == null || loginSessionId.isBlank()) {
            throw new IllegalArgumentException("登录会话 ID 不能为空");
        }

        Instant now = Instant.now();

        return Jwts.builder()
                .subject(String.valueOf(user.getId()))
                .claim("sid", loginSessionId)
                .claim("role", user.getRole())
                .issuedAt(Date.from(now))
                .expiration(Date.from(
                        now.plusSeconds(expirationSeconds)
                ))
                .signWith(secretKey)
                .compact();
    }

    /**
     * 解析并验证 JWT。
     *
     * 如果签名错误、格式错误、已过期或字段缺失，
     * 统一返回 401。
     */
    public JwtIdentity parse(String token) {
        if (token == null || token.isBlank()) {
            throw new BusinessException(401, "登录凭证不能为空");
        }

        try {
            Claims claims = Jwts.parser()
                    .verifyWith(secretKey)
                    .build()
                    .parseSignedClaims(token)
                    .getPayload();

            String subject = claims.getSubject();
            String loginSessionId = claims.get(
                    "sid",
                    String.class
            );

            if (subject == null || subject.isBlank()) {
                throw new IllegalArgumentException(
                        "JWT 缺少用户 ID"
                );
            }

            if (loginSessionId == null
                    || loginSessionId.isBlank()) {
                throw new IllegalArgumentException(
                        "JWT 缺少登录会话 ID"
                );
            }

            Long userId = Long.valueOf(subject);

            if (userId <= 0) {
                throw new IllegalArgumentException(
                        "JWT 用户 ID 无效"
                );
            }

            return new JwtIdentity(
                    userId,
                    loginSessionId
            );
        } catch (BusinessException exception) {
            throw exception;
        } catch (Exception exception) {
            /*
             * 不要把 JWT 的底层异常信息直接返回给客户端，
             * 避免暴露签名或解析细节。
             */
            throw new BusinessException(
                    401,
                    "登录凭证无效或已过期"
            );
        }
    }
}