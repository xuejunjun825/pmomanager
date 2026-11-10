package com.pmo.supervise.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pmo.supervise.common.ApiError;
import com.pmo.supervise.common.ErrorCode;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfFilter;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;
import org.springframework.security.web.context.NullSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Map;
import java.util.function.Supplier;

/**
 * 会话鉴权 + CSRF Cookie（TECHNICAL §4.3）。
 * 不使用 JWT：登出必须立即失效，会话是内网单实例最短路径。
 * 未认证统一返回 401 JSON，前端拦截后跳登录页。
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    private final ObjectMapper objectMapper;

    public SecurityConfig(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http, SessionAuthenticationFilter sessionAuthenticationFilter)
            throws Exception {
        SecurityContextRepository stateless = new NullSecurityContextRepository();
        http
                .csrf(csrf -> csrf
                        // 双提交 Cookie：axios 把 XSRF-TOKEN 原值放进 X-XSRF-TOKEN（http.ts:107-108 与
                        // scripts/smoke-api.sh），因此必须用明文处理器；Spring Security 6 默认的
                        // XorCsrfTokenRequestAttributeHandler 只接受掩码值，浏览器会全站写请求 403。
                        .csrfTokenRequestHandler(new CsrfTokenRequestAttributeHandler())
                        .csrfTokenRepository(CookieCsrfTokenRepository.withHttpOnlyFalse()))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/api/auth/**", "/actuator/health", "/error").permitAll()
                        .requestMatchers(org.springframework.http.HttpMethod.OPTIONS, "/**").permitAll()
                        .anyRequest().authenticated())
                .sessionManagement(session -> session
                        .sessionCreationPolicy(SessionCreationPolicy.IF_REQUIRED)
                        // 默认 changeSessionId 会在"本请求内刚认证"时换发 id（6.3 默认 requireExplicitSave
                        // 下 containsContext 恒 false，SessionManagementFilter 每请求都换），正是并发 401 的
                        // 根因；登录换发已由 AuthService.establish → AuthSession.rotateSessionId 显式完成。
                        .sessionFixation(fixation -> fixation.none()))
                // 载入侧：身份只来自 AUTH_USER（SessionAuthenticationFilter），会话里永不出现
                // SPRING_SECURITY_CONTEXT，陈旧上下文也就不会盖过 AUTH_USER。
                .securityContext(context -> context.securityContextRepository(stateless))
                .exceptionHandling(handling -> handling
                        .authenticationEntryPoint((request, response, ex) ->
                                writeError(request, response, ErrorCode.AUTH_REQUIRED))
                        .accessDeniedHandler((request, response, ex) ->
                                writeError(request, response, ErrorCode.FORBIDDEN)))
                .addFilterBefore(sessionAuthenticationFilter, UsernamePasswordAuthenticationFilter.class)
                .addFilterAfter(new CsrfCookieFilter(), CsrfFilter.class)
                .formLogin(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable);
        // 共享仓库决定 SessionManagementFilter 用哪个仓库判断 containsContext / 存上下文；
        // securityContext(...) 只决定载入侧（6.3.4 已核实两者互不回退）。必须 build 前设置：
        // SessionManagementConfigurer.init 在 build 内执行，取不到共享对象就会新建 HttpSession 版。
        http.setSharedObject(SecurityContextRepository.class, stateless);
        return http.build();
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    /** 任何 GET 请求访问时把 CSRF token 落到 XSRF-TOKEN cookie（前端 axios 自动回带）。 */
    static class CsrfCookieFilter extends OncePerRequestFilter {
        @Override
        protected void doFilterInternal(jakarta.servlet.http.HttpServletRequest request,
                                        jakarta.servlet.http.HttpServletResponse response,
                                        jakarta.servlet.FilterChain filterChain)
                throws jakarta.servlet.ServletException, IOException {
            Object attribute = request.getAttribute(CsrfToken.class.getName());
            if (attribute instanceof Supplier<?> supplier) {
                supplier.get();
            } else if (attribute instanceof CsrfToken token) {
                token.getToken();
            }
            filterChain.doFilter(request, response);
        }
    }

    private void writeError(jakarta.servlet.http.HttpServletRequest request,
                            HttpServletResponse response, ErrorCode code) throws IOException {
        response.setStatus(code.status().value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        String traceId = java.time.LocalDateTime.now() + "-"
                + java.util.UUID.randomUUID().toString().substring(0, 8);
        request.setAttribute("traceId", traceId);
        ApiError body = new ApiError(code.name(), code.defaultMessage(), java.util.List.of(),
                Map.of(), traceId);
        objectMapper.writeValue(response.getWriter(), body);
    }
}
