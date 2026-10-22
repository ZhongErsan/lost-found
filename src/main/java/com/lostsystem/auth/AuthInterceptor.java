package com.lostsystem.auth;

import com.lostsystem.entity.SysUser;
import com.lostsystem.exception.BusinessException;
import com.lostsystem.mapper.SysUserMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.AsyncHandlerInterceptor;

@Component
@RequiredArgsConstructor
public class AuthInterceptor implements AsyncHandlerInterceptor {

    private final JwtService jwtService;
    private final LoginSessionService loginSessionService;
    private final SysUserMapper sysUserMapper;

    @Override
    public boolean preHandle(
            HttpServletRequest request,
            HttpServletResponse response,
            Object handler
    ) {
        /*
         * 进入当前请求时，先清理当前线程可能残留的上下文。
         * 后续只有鉴权成功，才设置当前用户。
         */
        UserContext.clear();

        // 放行 CORS 预检请求。
        if ("OPTIONS".equalsIgnoreCase(request.getMethod())) {
            return true;
        }

        String authorization =
                request.getHeader("Authorization");

        if (authorization == null
                || !authorization.startsWith("Bearer ")) {
            throw new BusinessException(
                    401,
                    "请先登录"
            );
        }

        String token = authorization
                .substring("Bearer ".length())
                .trim();

        if (token.isBlank()) {
            throw new BusinessException(
                    401,
                    "登录凭证不能为空"
            );
        }

        // 验证 JWT 签名、有效期并读取身份。
        JwtIdentity identity = jwtService.parse(token);

        if (identity == null
                || identity.userId() == null
                || identity.userId() <= 0
                || identity.loginSessionId() == null
                || identity.loginSessionId().isBlank()) {
            throw new BusinessException(
                    401,
                    "登录凭证无效"
            );
        }

        // 验证当前 Token 是否仍然对应 Redis 中的有效会话。
        boolean currentSession =
                loginSessionService.isCurrentSession(
                        identity.userId(),
                        identity.loginSessionId()
                );

        if (!currentSession) {
            throw new BusinessException(
                    401,
                    "登录状态已失效，请重新登录"
            );
        }

        // 查询用户最新状态，使禁用操作能够及时生效。
        SysUser user =
                sysUserMapper.selectById(identity.userId());

        if (user == null) {
            throw new BusinessException(
                    401,
                    "用户不存在"
            );
        }

        if (!"ACTIVE".equalsIgnoreCase(user.getStatus())) {
            throw new BusinessException(
                    403,
                    "账号已被禁用"
            );
        }

        // 所有鉴权检查通过后，设置当前线程的用户信息。
        UserContext.set(
                CurrentUser.from(
                        user,
                        identity.loginSessionId()
                )
        );

        return true;
    }

    /**
     * 请求进入异步处理、原请求线程释放前执行。
     *
     * 必须清理原线程上的 ThreadLocal。
     */
    @Override
    public void afterConcurrentHandlingStarted(
            HttpServletRequest request,
            HttpServletResponse response,
            Object handler
    ) {
        UserContext.clear();
    }

    /**
     * 同步请求结束，或异步重新分派完成后执行。
     *
     * 清理当前处理线程上的 ThreadLocal。
     */
    @Override
    public void afterCompletion(
            HttpServletRequest request,
            HttpServletResponse response,
            Object handler,
            Exception exception
    ) {
        UserContext.clear();
    }
}