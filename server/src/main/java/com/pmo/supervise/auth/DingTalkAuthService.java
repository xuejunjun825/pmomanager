package com.pmo.supervise.auth;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pmo.supervise.auth.dto.AuthUser;
import com.pmo.supervise.common.BusinessException;
import com.pmo.supervise.common.ErrorCode;
import com.pmo.supervise.common.Role;
import com.pmo.supervise.common.UserStatus;
import com.pmo.supervise.config.AppProperties;
import com.pmo.supervise.config.DingTalkProperties;
import com.pmo.supervise.user.User;
import com.pmo.supervise.user.UserRepository;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.env.Environment;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * 钉钉扫码与免登（AC-18 / TECHNICAL §4.8）。
 *
 * <p>钉钉只提供身份，不提供角色：新建档一律 OWNER + ACTIVE，角色由管理员在用户管理里手工分配。
 * 未配置时整条链路立即 503，不尝试任何外网请求；运行期失败按 401 处理并写审计。</p>
 */
@Service
public class DingTalkAuthService {

    private static final Logger log = LoggerFactory.getLogger(DingTalkAuthService.class);

    /** 新 API 域名（login.dingtalk.com 只负责授权页）；测试可用 supervise.dingtalk.oauth-base 指到本地桩。 */
    private static final String DEFAULT_OAUTH_BASE = "https://api.dingtalk.com";

    private final DingTalkProperties properties;
    private final AppProperties appProperties;
    private final DingTalkStateStore stateStore;
    private final UserRepository users;
    private final AuthService authService;
    private final LoginAuditService audit;
    private final ObjectMapper objectMapper;
    private final RestClient restClient;
    private final String oauthBase;

    public DingTalkAuthService(DingTalkProperties properties, AppProperties appProperties,
                               DingTalkStateStore stateStore, UserRepository users, AuthService authService,
                               LoginAuditService audit, ObjectMapper objectMapper, Environment environment) {
        this.properties = properties;
        this.appProperties = appProperties;
        this.stateStore = stateStore;
        this.users = users;
        this.authService = authService;
        this.audit = audit;
        this.objectMapper = objectMapper;
        this.oauthBase = trimTrailingSlash(
                environment.getProperty("supervise.dingtalk.oauth-base", DEFAULT_OAUTH_BASE));
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(3000);
        factory.setReadTimeout(5000);
        this.restClient = RestClient.builder().requestFactory(factory).build();
    }

    /** 生成钉钉授权页地址；state 同时写入 session（5 分钟、单次消费）。 */
    public String authorizeUrl(String next, HttpServletRequest request) {
        requireConfigured(request);
        String state = stateStore.issue(request, SessionUser.safeNext(next));
        String redirectUri = trimTrailingSlash(appProperties.getFrontendBaseUrl()) + "/api/auth/dingtalk/callback";
        return trimTrailingSlash(properties.getLoginBase()) + "/oauth2/auth"
                + "?redirect_uri=" + encode(redirectUri)
                + "&response_type=code"
                + "&client_id=" + encode(properties.getAppKey())
                + "&scope=openid"
                + "&state=" + encode(state)
                + "&prompt=consent";
    }

    /**
     * 扫码回调：校验 state → code 换身份 → 建档/更新 → 建会话。next 为空表示由前端按角色落地。
     *
     * <p>刻意不加 @Transactional：state 校验失败要先把审计落库再抛 400，
     * 而首次登录的唯一键冲突恢复（{@link #createFromDingTalk}）也需要 save 立即独立提交。</p>
     */
    public CallbackResult handleCallback(String code, String state, HttpServletRequest request) {
        requireConfigured(request);
        Optional<DingTalkStateStore.Consumed> consumed = stateStore.consume(request, state);
        if (consumed.isEmpty()) {
            audit.recordAnonymous(LoginAuditService.DINGTALK_STATE_INVALID, "DINGTALK", null,
                    "state 校验失败（伪造、过期或已消费）", request);
            throw new BusinessException(ErrorCode.AUTH_STATE_INVALID, "登录请求已失效，请重新发起钉钉登录");
        }
        User user = loginByCode(code, request);
        return new CallbackResult(AuthUser.from(user), SessionUser.safeNext(consumed.get().next()));
    }

    /** 钉钉容器内免登：没有 state（code 由 jsapi 现场签发），其余流程与扫码一致。 */
    public AuthUser autoLogin(String authCode, String next, HttpServletRequest request) {
        requireConfigured(request);
        User user = loginByCode(authCode, request);
        return AuthUser.from(user);
    }

    private User loginByCode(String code, HttpServletRequest request) {
        if (code == null || code.isBlank()) {
            audit.recordAnonymous(LoginAuditService.DINGTALK_CODE_INVALID, "DINGTALK", null,
                    "缺少 authCode", request);
            throw new BusinessException(ErrorCode.AUTH_BAD_CREDENTIALS, "钉钉登录失败，请使用账号密码登录");
        }

        Identity identity;
        try {
            identity = exchange(code);
        } catch (RuntimeException ex) {
            log.warn("dingtalk code exchange failed: {}", ex.getMessage());
            audit.recordAnonymous(LoginAuditService.DINGTALK_CODE_INVALID, "DINGTALK", null,
                    "code 换取身份失败（code 无效或钉钉接口不可用）", request);
            throw new BusinessException(ErrorCode.AUTH_BAD_CREDENTIALS, "钉钉登录失败，请使用账号密码登录");
        }

        User user = users.findByDingtalkUnionId(identity.unionId()).orElse(null);
        if (user == null) {
            user = createFromDingTalk(identity);
        } else {
            if (user.getStatus() == UserStatus.DISABLED) {
                audit.record(user.getId(), LoginAuditService.LOGIN_DISABLED, "USER",
                        String.valueOf(user.getId()), "已停用账号尝试钉钉登录", request);
                throw new BusinessException(ErrorCode.AUTH_DISABLED);
            }
            boolean changed = false;
            if (identity.nick() != null && !identity.nick().isBlank() && !identity.nick().equals(user.getName())) {
                user.setName(identity.nick());
                changed = true;
            }
            if (identity.avatarUrl() != null && !identity.avatarUrl().isBlank()
                    && !identity.avatarUrl().equals(user.getAvatarUrl())) {
                user.setAvatarUrl(identity.avatarUrl());
                changed = true;
            }
            if (changed) {
                user = users.save(user);
            }
        }

        audit.record(user.getId(), LoginAuditService.DINGTALK_LOGIN, "USER", String.valueOf(user.getId()),
                "钉钉免登建档/登录", request);
        authService.establish(user, request);
        return user;
    }

    private User createFromDingTalk(Identity identity) {
        User user = new User();
        user.setDingtalkUnionId(identity.unionId());
        user.setName(identity.nick() == null || identity.nick().isBlank() ? "钉钉用户" : identity.nick());
        user.setAvatarUrl(identity.avatarUrl());
        user.setRole(Role.OWNER);
        user.setStatus(UserStatus.ACTIVE);
        user.setMustChangePassword(false);
        try {
            return users.save(user);
        } catch (org.springframework.dao.DataIntegrityViolationException ex) {
            // 并发首登：唯一键 uk_users_union 兜底，重新读取已建好的那一行。
            return users.findByDingtalkUnionId(identity.unionId())
                    .orElseThrow(() -> ex);
        }
    }

    /** code → userAccessToken → contact/users/me。任何网络/协议失败都抛 RuntimeException，由上层转 401。 */
    private Identity exchange(String code) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("clientId", properties.getAppKey());
        payload.put("clientSecret", properties.getAppSecret());
        payload.put("code", code);
        payload.put("grantType", "authorization_code");

        String tokenBody = restClient.post()
                .uri(oauthBase + "/v1.0/oauth2/userAccessToken")
                .contentType(MediaType.APPLICATION_JSON)
                .body(payload)
                .retrieve()
                .body(String.class);
        JsonNode token = readTree(tokenBody);
        String accessToken = token.path("accessToken").asText(null);
        if (accessToken == null || accessToken.isBlank()) {
            throw new IllegalStateException("dingtalk accessToken missing");
        }

        String userBody = restClient.get()
                .uri(oauthBase + "/v1.0/contact/users/me")
                .header("x-acs-dingtalk-access-token", accessToken)
                .retrieve()
                .body(String.class);
        JsonNode me = readTree(userBody);
        String unionId = me.path("unionId").asText(null);
        if (unionId == null || unionId.isBlank()) {
            throw new IllegalStateException("dingtalk unionId missing");
        }
        return new Identity(unionId, me.path("nick").asText(null), me.path("avatarUrl").asText(null));
    }

    private JsonNode readTree(String body) {
        try {
            return objectMapper.readTree(body == null ? "{}" : body);
        } catch (Exception ex) {
            throw new IllegalStateException("dingtalk response is not json", ex);
        }
    }

    private void requireConfigured(HttpServletRequest request) {
        if (properties.isConfigured()) {
            return;
        }
        audit.recordAnonymous(LoginAuditService.DINGTALK_NOT_CONFIGURED, "DINGTALK", null,
                "钉钉配置缺失：" + String.join(",", properties.missingKeys()), request);
        throw new BusinessException(ErrorCode.DINGTALK_NOT_CONFIGURED,
                "钉钉登录尚未配置，请使用账号密码登录");
    }

    private static String encode(String value) {
        return URLEncoder.encode(value == null ? "" : value, StandardCharsets.UTF_8);
    }

    private static String trimTrailingSlash(String value) {
        if (value == null || value.isEmpty()) {
            return "";
        }
        return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
    }

    /** 回调结果：user 用于建会话，next 用于 302 回前端 /sso/callback。 */
    public record CallbackResult(AuthUser user, String next) {
    }

    private record Identity(String unionId, String nick, String avatarUrl) {
    }
}
