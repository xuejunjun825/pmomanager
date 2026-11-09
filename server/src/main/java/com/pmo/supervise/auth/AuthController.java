package com.pmo.supervise.auth;

import com.pmo.supervise.auth.dto.AuthUser;
import com.pmo.supervise.auth.dto.LoginRequest;
import com.pmo.supervise.common.Actor;
import com.pmo.supervise.config.AppProperties;
import com.pmo.supervise.config.DingTalkProperties;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

/**
 * /api/auth 全部路由（TECHNICAL §5.1）：config / login / logout / me / password / dingtalk。
 *
 * <p>整段路径在 SecurityConfig 里 permitAll，因此每个需要身份的入口显式接收 {@link Actor}
 * （由 ActorArgumentResolver 从会话解析，未登录抛 401）。</p>
 */
@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final AuthService authService;
    private final DingTalkAuthService dingTalkAuthService;
    private final DingTalkProperties dingTalkProperties;
    private final AppProperties appProperties;

    public AuthController(AuthService authService, DingTalkAuthService dingTalkAuthService,
                          DingTalkProperties dingTalkProperties, AppProperties appProperties) {
        this.authService = authService;
        this.dingTalkAuthService = dingTalkAuthService;
        this.dingTalkProperties = dingTalkProperties;
        this.appProperties = appProperties;
    }

    /** 登录页启动配置：是否显示钉钉入口、缺哪些配置项（只报名称，不返回值）。 */
    @GetMapping("/config")
    public AuthConfig config() {
        return new AuthConfig(dingTalkProperties.isConfigured(),
                dingTalkProperties.isConfigured() ? List.of() : dingTalkProperties.missingKeys(),
                true,
                appProperties.getSiteName());
    }

    @PostMapping("/login")
    public AuthUser login(@Valid @RequestBody LoginRequest request, HttpServletRequest httpRequest) {
        return authService.login(request.username(), request.password(), httpRequest);
    }

    @PostMapping("/logout")
    public ResponseEntity<Void> logout(HttpServletRequest httpRequest) {
        authService.logout(httpRequest);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/me")
    public AuthUser me(Actor actor, HttpServletRequest httpRequest) {
        return authService.me(actor, httpRequest);
    }

    @PostMapping("/password")
    public ResponseEntity<Void> changePassword(@Valid @RequestBody ChangePasswordRequest request,
                                               Actor actor, HttpServletRequest httpRequest) {
        authService.changePassword(actor.id(), request.oldPassword(), request.newPassword(), httpRequest);
        return ResponseEntity.noContent().build();
    }

    /** 扫码登录第一步：返回钉钉授权页地址；未配置 → 503 DINGTALK_NOT_CONFIGURED。 */
    @GetMapping("/dingtalk/authorize-url")
    public Map<String, String> authorizeUrl(@RequestParam(required = false) String next,
                                            HttpServletRequest httpRequest) {
        return Map.of("authorizeUrl", dingTalkAuthService.authorizeUrl(next, httpRequest));
    }

    /** 扫码回调：钉钉带 code/state 回到本站，成功后 302 到前端 /sso/callback（next 透传）。 */
    @GetMapping("/dingtalk/callback")
    public ResponseEntity<Void> callback(@RequestParam(required = false) String code,
                                         @RequestParam(required = false) String state,
                                         HttpServletRequest httpRequest) {
        DingTalkAuthService.CallbackResult result = dingTalkAuthService.handleCallback(code, state, httpRequest);
        String target = trimTrailingSlash(appProperties.getFrontendBaseUrl()) + "/sso/callback";
        if (result.next() != null && !result.next().isBlank()) {
            target += "?next=" + URLEncoder.encode(result.next(), StandardCharsets.UTF_8);
        }
        return ResponseEntity.status(HttpStatus.FOUND).location(URI.create(target)).build();
    }

    /** 钉钉容器内免登：jsapi 现场取 authCode，直接换会话。 */
    @PostMapping("/dingtalk/auto")
    public AuthUser autoLogin(@Valid @RequestBody DingTalkAutoRequest request,
                              HttpServletRequest httpRequest) {
        return dingTalkAuthService.autoLogin(request.authCode(), request.next(), httpRequest);
    }

    private static String trimTrailingSlash(String value) {
        if (value == null || value.isEmpty()) {
            return "";
        }
        return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
    }

    /** 与 web/src/types/api.ts AuthConfig 一一对应。 */
    public record AuthConfig(boolean dingtalkConfigured, List<String> missingKeys, boolean passwordLogin,
                             String siteName) {
    }

    public record ChangePasswordRequest(@NotBlank @Size(max = 128) String oldPassword,
                                        @NotBlank @Size(max = 128) String newPassword) {
    }

    public record DingTalkAutoRequest(@NotBlank @Size(max = 512) String authCode,
                                      @Size(max = 300) String next) {
    }
}
