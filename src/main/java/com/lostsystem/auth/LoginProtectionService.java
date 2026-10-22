package com.lostsystem.auth;

import com.lostsystem.exception.BusinessException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Locale;

@Service
public class LoginProtectionService {

    private static final String PREFIX =
            "lost-found:security:login:";

    /*
     * IP 请求计数。
     *
     * 第一次请求时设置过期时间，之后在这个时间窗口内累计次数。
     * Lua 脚本保证计数和设置过期时间作为一个整体执行。
     */
    private static final DefaultRedisScript<Long> REQUEST_RATE_SCRIPT =
            new DefaultRedisScript<>(
                    """
                    local count = redis.call('INCR', KEYS[1])
                    if count == 1 then
                        redis.call('EXPIRE', KEYS[1], ARGV[1])
                    end
                    return count
                    """,
                    Long.class
            );

    /*
     * 密码错误计数。
     *
     * KEYS[1]：密码错误计数 key。
     * KEYS[2]：账号临时锁定 key。
     *
     * ARGV[1]：错误次数上限。
     * ARGV[2]：错误统计窗口，单位秒。
     * ARGV[3]：锁定时长，单位秒。
     */
    private static final DefaultRedisScript<Long> PASSWORD_FAILURE_SCRIPT =
            new DefaultRedisScript<>(
                    """
                    if redis.call('EXISTS', KEYS[2]) == 1 then
                        return -1
                    end

                    local count = redis.call('INCR', KEYS[1])

                    if count == 1 then
                        redis.call('EXPIRE', KEYS[1], ARGV[2])
                    end

                    if count >= tonumber(ARGV[1]) then
                        redis.call('SET', KEYS[2], '1', 'EX', ARGV[3])
                        redis.call('DEL', KEYS[1])
                    end

                    return count
                    """,
                    Long.class
            );

    private final StringRedisTemplate redisTemplate;
    private final int requestLimit;
    private final int requestWindowSeconds;
    private final int maxPasswordFailures;
    private final int failureWindowSeconds;
    private final int lockSeconds;

    public LoginProtectionService(
            StringRedisTemplate redisTemplate,
            @Value("${app.login-protection.request-limit:30}")
            int requestLimit,
            @Value("${app.login-protection.request-window-seconds:60}")
            int requestWindowSeconds,
            @Value("${app.login-protection.max-password-failures:5}")
            int maxPasswordFailures,
            @Value("${app.login-protection.failure-window-seconds:600}")
            int failureWindowSeconds,
            @Value("${app.login-protection.lock-seconds:600}")
            int lockSeconds
    ) {
        if (requestLimit <= 0
                || requestWindowSeconds <= 0
                || maxPasswordFailures <= 0
                || failureWindowSeconds <= 0
                || lockSeconds <= 0) {
            throw new IllegalStateException(
                    "登录防护配置必须全部大于 0"
            );
        }

        this.redisTemplate = redisTemplate;
        this.requestLimit = requestLimit;
        this.requestWindowSeconds = requestWindowSeconds;
        this.maxPasswordFailures = maxPasswordFailures;
        this.failureWindowSeconds = failureWindowSeconds;
        this.lockSeconds = lockSeconds;
    }

    /**
     * 限制同一个 IP 的登录请求频率。
     */
    public void checkRequestRate(String ip) {
        try {
            Long count = redisTemplate.execute(
                    REQUEST_RATE_SCRIPT,
                    List.of(PREFIX + "rate:ip:" + ip),
                    String.valueOf(requestWindowSeconds)
            );

            if (count == null) {
                throw unavailable();
            }

            if (count > requestLimit) {
                throw new BusinessException(
                        429,
                        "登录请求过于频繁，请稍后再试"
                );
            }
        } catch (DataAccessException exception) {
            throw unavailable(exception);
        }
    }

    /**
     * 密码校验前检查账号是否暂时锁定。
     */
    public void checkPasswordLock(String username) {
        try {
            Boolean locked = redisTemplate.hasKey(
                    lockKey(username)
            );

            if (locked == null) {
                throw unavailable();
            }

            if (locked) {
                throw new BusinessException(
                        429,
                        "密码错误次数过多，请稍后再试"
                );
            }
        } catch (DataAccessException exception) {
            throw unavailable(exception);
        }
    }

    /**
     * 记录密码错误；达到阈值时临时锁定账号。
     */
    public void recordPasswordFailure(String username) {
        try {
            Long count = redisTemplate.execute(
                    PASSWORD_FAILURE_SCRIPT,
                    List.of(
                            failureKey(username),
                            lockKey(username)
                    ),
                    String.valueOf(maxPasswordFailures),
                    String.valueOf(failureWindowSeconds),
                    String.valueOf(lockSeconds)
            );

            if (count == null) {
                throw unavailable();
            }

            if (count == -1L || count >= maxPasswordFailures) {
                throw new BusinessException(
                        429,
                        "密码错误次数过多，请稍后再试"
                );
            }
        } catch (DataAccessException exception) {
            throw unavailable(exception);
        }
    }

    /**
     * 登录校验成功后清除密码错误计数。
     */
    public void clearPasswordFailures(String username) {
        try {
            redisTemplate.delete(failureKey(username));
        } catch (DataAccessException exception) {
            throw unavailable(exception);
        }
    }

    private String failureKey(String username) {
        return PREFIX + "failure:user:" + normalizeUsername(username);
    }

    private String lockKey(String username) {
        return PREFIX + "lock:user:" + normalizeUsername(username);
    }

    private String normalizeUsername(String username) {
        return username.trim().toLowerCase(Locale.ROOT);
    }

    private BusinessException unavailable() {
        return new BusinessException(
                503,
                "登录服务暂不可用，请稍后重试"
        );
    }

    private BusinessException unavailable(
            DataAccessException cause
    ) {
        return new BusinessException(
                503,
                "登录服务暂不可用，请稍后重试",
                cause
        );
    }
}