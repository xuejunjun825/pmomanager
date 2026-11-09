package com.pmo.supervise.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pmo.supervise.common.ApiError;
import com.pmo.supervise.common.ErrorCode;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 首登强制改密闸门（AC-1 / TECHNICAL §4.8）：must_change_password=1 的会话，
 * 除“取当前用户 / 改密 / 登出 / 登录 / 钉钉登录”之外的任何 /api 请求一律 409
 * PASSWORD_CHANGE_REQUIRED。放在 Filter 里而不是每个 Controller 里判，
 * 新加接口不会漏掉这道闸。
 */
@Component
@Order(Ordered.LOWEST_PRECEDENCE - 10)
public class MustChangePasswordFilter extends OncePerRequestFilter {

    private static final Set<String> EXEMPT_PATHS = Set.of(
            "/api/auth/config",
            "/api/auth/login",
            "/api/auth/logout",
            "/api/auth/me",
            "/api/auth/password");

    private final ObjectMapper objectMapper;

    public MustChangePasswordFilter(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String path = request.getRequestURI().substring(request.getContextPath().length());
        boolean gated = path.startsWith("/api/")
                && !path.startsWith("/api/auth/dingtalk/")
                && !EXEMPT_PATHS.contains(path);
        if (!gated || !SessionUser.mustChangePassword(request)) {
            chain.doFilter(request, response);
            return;
        }

        response.setStatus(409);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        String traceId = LocalDateTime.now() + "-" + UUID.randomUUID().toString().substring(0, 8);
        request.setAttribute("traceId", traceId);
        ApiError body = new ApiError(ErrorCode.PASSWORD_CHANGE_REQUIRED.name(),
                "首次登录请先修改密码", List.of(), Map.of(), traceId);
        objectMapper.writeValue(response.getWriter(), body);
    }
}
