package com.pmo.supervise.common;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;

/**
 * 会话身份的唯一定义（T01 提供，T03 写入）。
 *
 * <p>登录成功后调用 {@link #signIn(HttpServletRequest, Actor)}，或把 Actor 作为
 * SecurityContext 的 principal。两种方式本类都能读出，任务/通知等业务模块只依赖本类，
 * 不感知认证模块的实现细节。</p>
 */
public final class AuthSession {

    public static final String ATTR = "AUTH_USER";

    private AuthSession() {
    }

    /**
     * 登录成功时换发会话 id —— 会话固定防护的唯一入口。
     * 无会话时不动：新会话 id 由随后的 getSession(true) 生成，登录后自然与匿名期不同。
     *
     * <p>与 {@link #signIn(HttpServletRequest, Actor)} 解耦：角色刷新（me）也调 signIn，
     * 若把换发挂在 signIn 上，刷新角色就会换 id。只有登录汇合点可调用本方法。</p>
     */
    public static void rotateSessionId(HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        if (session != null) {
            request.changeSessionId();   // 只改 id，属性全部保留；容器据此下发新 Set-Cookie
        }
    }

    /** 写入会话并使 SecurityContext 立即可用（同一请求后续代码可读到）。 */
    public static void signIn(HttpServletRequest request, Actor actor) {
        HttpSession session = request.getSession(true);
        session.setAttribute(ATTR, actor);
        SecurityContextHolder.getContext().setAuthentication(authenticationOf(actor));
    }

    public static void signOut(HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        if (session != null) {
            session.removeAttribute(ATTR);
            session.invalidate();
        }
        SecurityContextHolder.clearContext();
    }

    /** 当前请求的操作者；未登录返回 null。 */
    public static Actor current(HttpServletRequest request) {
        Object attribute = request.getAttribute(ATTR);
        if (attribute instanceof Actor actor) {
            return actor;
        }
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null && authentication.getPrincipal() instanceof Actor actor) {
            return actor;
        }
        HttpSession session = request.getSession(false);
        if (session != null && session.getAttribute(ATTR) instanceof Actor sessionActor) {
            return sessionActor;
        }
        return null;
    }

    public static UsernamePasswordAuthenticationToken authenticationOf(Actor actor) {
        return new UsernamePasswordAuthenticationToken(
                actor, null, List.of(new SimpleGrantedAuthority("ROLE_" + actor.role().name())));
    }
}
