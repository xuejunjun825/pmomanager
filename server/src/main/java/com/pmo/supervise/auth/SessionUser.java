package com.pmo.supervise.auth;

import com.pmo.supervise.common.Actor;
import com.pmo.supervise.common.AuthSession;
import com.pmo.supervise.common.Role;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;

/**
 * 认证模块对会话的补充约定：除 Actor 之外，还记录“是否必须改密”。
 *
 * <p>登录成功后由 {@link #signIn(HttpServletRequest, Actor, boolean)} 写入；改密成功后清除。
 * 强制改密由 {@link MustChangePasswordFilter} 统一拦截，避免每个接口各判一次。</p>
 */
public final class SessionUser {

    /** 会话属性：must_change_password=1 时必须先改密。 */
    public static final String MUST_CHANGE_ATTR = "AUTH_MUST_CHANGE_PASSWORD";

    private SessionUser() {
    }

    public static void signIn(HttpServletRequest request, Actor actor, boolean mustChangePassword) {
        AuthSession.signIn(request, actor);
        request.getSession(true).setAttribute(MUST_CHANGE_ATTR, mustChangePassword);
    }

    public static boolean mustChangePassword(HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        return session != null && Boolean.TRUE.equals(session.getAttribute(MUST_CHANGE_ATTR));
    }

    public static void clearMustChangePassword(HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        if (session != null) {
            session.setAttribute(MUST_CHANGE_ATTR, false);
        }
    }

    /** 角色落地页：管理员进任务池、负责人进我的任务、管理层进看板（与前端 permissions.ts 一致）。 */
    public static String landingPath(Role role) {
        if (role == null) {
            return "/tasks";
        }
        return switch (role) {
            case ADMIN -> "/tasks";
            case EXEC -> "/dashboard";
            case OWNER -> "/my";
        };
    }

    /**
     * 只接受站内绝对路径，阻断 ?next=https://evil 这类开放重定向。
     * 反斜杠与 CR/LF 一律拒绝（浏览器会把它解释成协议相对地址或响应头注入）。
     */
    public static String safeNext(String next) {
        if (next == null || next.isBlank()) {
            return null;
        }
        String value = next.trim();
        if (!value.startsWith("/") || value.startsWith("//")) {
            return null;
        }
        if (value.indexOf('\\') >= 0 || value.indexOf('\r') >= 0 || value.indexOf('\n') >= 0) {
            return null;
        }
        return value.length() > 300 ? value.substring(0, 300) : value;
    }
}
