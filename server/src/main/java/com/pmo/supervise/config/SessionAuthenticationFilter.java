package com.pmo.supervise.config;

import com.pmo.supervise.common.Actor;
import com.pmo.supervise.common.AuthSession;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * 把会话中的 Actor 同步进 SecurityContext（T03 可只写 session，也可只写 context）。
 * 这样认证模块与业务模块不必互相 import 具体实现。
 */
@Component
public class SessionAuthenticationFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (SecurityContextHolder.getContext().getAuthentication() == null) {
            HttpSession session = request.getSession(false);
            if (session != null && session.getAttribute(AuthSession.ATTR) instanceof Actor actor) {
                SecurityContextHolder.getContext().setAuthentication(AuthSession.authenticationOf(actor));
            }
        }
        try {
            chain.doFilter(request, response);
        } finally {
            SecurityContextHolder.clearContext();
        }
    }
}
