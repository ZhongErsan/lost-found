package com.lostsystem.auth;

import com.lostsystem.exception.BusinessException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Collections;
import java.util.Objects;

@Service
public class LoginSessionService {

    /**
     * 只有 Redis 中的 sessionId 与当前请求一致时，
     * 才删除会话。
     *
     * 这样可以避免旧设备退出时误删新设备的登录会话。
     */
    private static final DefaultRedisScript<Long>
            DELETE_IF_MATCH_SCRIPT =
            new DefaultRedisScript<>(
                    """
                    if redis.call('get', KEYS[1]) == ARGV[1]
                    then
                        return redis.call('del', KEYS[1])
                    else
                        return 0
                    end
                    """,
                    Long.class
            );

    private final StringRedisTemplate redisTemplate;
    private final Duration sessionTtl;
    private final String keyPrefix;

    public LoginSessionService(
            StringRedisTemplate redisTemplate,
            @Value("${app.jwt.expiration-seconds:7200}")
            long expirationSeconds,
            @Value("${app.auth.redis-session-prefix:lost-found:auth:session:}")
            String keyPrefix
    ) {
        if (expirationSeconds <= 0) {
            throw new IllegalStateException(
                    "app.jwt.expiration-seconds 必须大于 0"
            );
        }

        this.redisTemplate = redisTemplate;
        this.sessionTtl = Duration.ofSeconds(
                expirationSeconds
        );
        this.keyPrefix = keyPrefix;
    }

    /**
     * 登录成功时保存最新登录会话。
     *
     * 同一个 userId 只有一个有效 loginSessionId，
     * 后登录的会覆盖先登录的会话。
     */
    public void replaceSession(
            Long userId,
            String loginSessionId
    ) {
        requireArguments(userId, loginSessionId);

        try {
            redisTemplate.opsForValue().set(
                    key(userId),
                    loginSessionId,
                    sessionTtl
            );
        } catch (DataAccessException exception) {
            throw new BusinessException(
                    503,
                    "登录会话服务暂不可用，请稍后重试",
                    exception
            );
        }
    }

    /**
     * 校验当前 Token 中的登录会话是否仍然有效。
     */
    public boolean isCurrentSession(
            Long userId,
            String loginSessionId
    ) {
        requireArguments(userId, loginSessionId);

        try {
            String currentSessionId =
                    redisTemplate.opsForValue().get(key(userId));

            return Objects.equals(
                    currentSessionId,
                    loginSessionId
            );
        } catch (DataAccessException exception) {
            throw new BusinessException(
                    503,
                    "登录会话服务暂不可用，请稍后重试",
                    exception
            );
        }
    }

    /**
     * 用户主动退出登录。
     *
     * 只有当前请求的登录会话与 Redis 中保存的会话一致时，
     * 才允许删除。
     */
    public boolean removeSessionIfMatch(
            Long userId,
            String loginSessionId
    ) {
        requireArguments(userId, loginSessionId);

        try {
            Long result = redisTemplate.execute(
                    DELETE_IF_MATCH_SCRIPT,
                    Collections.singletonList(key(userId)),
                    loginSessionId
            );

            return Long.valueOf(1L).equals(result);
        } catch (DataAccessException exception) {
            throw new BusinessException(
                    503,
                    "登录会话服务暂不可用，请稍后重试",
                    exception
            );
        }
    }

    /**
     * 强制让用户当前登录会话失效。
     *
     * 可以用于：
     * - 管理员强制踢人；
     * - 修改密码；
     * - 禁用用户；
     * - 注销账号。
     */
    public void invalidate(Long userId) {
        if (userId == null || userId <= 0) {
            throw new IllegalArgumentException(
                    "用户 ID 必须是正数"
            );
        }

        try {
            redisTemplate.delete(key(userId));
        } catch (DataAccessException exception) {
            throw new BusinessException(
                    503,
                    "登录会话服务暂不可用，请稍后重试",
                    exception
            );
        }
    }

    private void requireArguments(
            Long userId,
            String loginSessionId
    ) {
        if (userId == null || userId <= 0) {
            throw new IllegalArgumentException(
                    "用户 ID 必须是正数"
            );
        }

        if (loginSessionId == null
                || loginSessionId.isBlank()) {
            throw new IllegalArgumentException(
                    "登录会话 ID 不能为空"
            );
        }
    }

    private String key(Long userId) {
        return keyPrefix + userId;
    }
}