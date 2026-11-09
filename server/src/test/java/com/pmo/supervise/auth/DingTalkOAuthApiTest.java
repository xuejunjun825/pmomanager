package com.pmo.supervise.auth;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pmo.supervise.IntegrationTestSupport;
import com.pmo.supervise.common.AuthSession;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * 钉钉扫码/免登全链路（AC-18 / AC-21）：用一个本地 HTTP 桩替代 open.dingtalk.com，
 * 校验伪造 state → 400 且留痕、无效 code → 401 且留痕、state 单次消费、
 * 首登默认 OWNER、open redirect 被丢弃。
 */
@SpringBootTest(properties = {
        "supervise.dingtalk.corp-id=ding-corp",
        "supervise.dingtalk.agent-id=1001",
        "supervise.dingtalk.app-key=ding-app-key",
        "supervise.dingtalk.app-secret=ding-app-secret",
        "supervise.dingtalk.oauth-base=http://127.0.0.1:18089"
})
@AutoConfigureMockMvc
class DingTalkOAuthApiTest extends IntegrationTestSupport {

    /** 响应体按 UTF-8 解码，断言里的中文昵称才可比。 */
    @org.springframework.boot.test.context.TestConfiguration
    static class Utf8Responses {
        @org.springframework.context.annotation.Bean
        org.springframework.boot.test.autoconfigure.web.servlet.MockMvcBuilderCustomizer utf8() {
            return builder -> builder.defaultResponseCharacterEncoding(StandardCharsets.UTF_8);
        }
    }

    private static final int STUB_PORT = 18089;
    private static final String UNION_ID = "union-1";
    private static final Pattern STATE = Pattern.compile("[?&]state=([^&]+)");

    private static HttpServer stub;

    @Autowired
    private MockMvc mvc;

    @Autowired
    private ObjectMapper json;

    @BeforeAll
    static void startStub() throws IOException {
        stub = HttpServer.create(new InetSocketAddress("127.0.0.1", STUB_PORT), 0);
        stub.createContext("/v1.0/oauth2/userAccessToken", exchange -> {
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            if (body.contains("bad-code")) {
                respond(exchange, 400, "{\"code\":\"invalidCode\",\"message\":\"code 无效\"}");
                return;
            }
            respond(exchange, 200, "{\"accessToken\":\"tok-1\",\"expireIn\":7200}");
        });
        stub.createContext("/v1.0/contact/users/me", exchange -> {
            if (!"tok-1".equals(exchange.getRequestHeaders().getFirst("x-acs-dingtalk-access-token"))) {
                respond(exchange, 401, "{\"code\":\"invalidToken\"}");
                return;
            }
            respond(exchange, 200, "{\"unionId\":\"" + UNION_ID + "\",\"nick\":\"钉钉小王\","
                    + "\"avatarUrl\":\"https://static.dingtalk.com/a.png\"}");
        });
        stub.start();
    }

    @AfterAll
    static void stopStub() {
        if (stub != null) {
            stub.stop(0);
        }
    }

    @Test
    void forgedStateIsRejectedWith400AndAudited() throws Exception {
        MvcResult result = mvc.perform(get("/api/auth/dingtalk/callback")
                .param("code", "good-code").param("state", "forged")).andReturn();

        assertThat(result.getResponse().getStatus()).isEqualTo(400);
        assertThat(json.readTree(result.getResponse().getContentAsString()).get("code").asText())
                .isEqualTo("AUTH_STATE_INVALID");
        assertThat(auditCount("DINGTALK_STATE_INVALID")).isEqualTo(1);
    }

    @Test
    void stateIsSingleUseAndInvalidCodeIsRejectedWith401() throws Exception {
        MockHttpSession session = new MockHttpSession();
        String state = authorize(session, "/my");

        MvcResult badCode = mvc.perform(get("/api/auth/dingtalk/callback").session(session)
                .param("code", "bad-code").param("state", state)).andReturn();
        assertThat(badCode.getResponse().getStatus()).isEqualTo(401);
        assertThat(json.readTree(badCode.getResponse().getContentAsString()).get("code").asText())
                .isEqualTo("AUTH_BAD_CREDENTIALS");
        assertThat(auditCount("DINGTALK_CODE_INVALID")).isEqualTo(1);

        // 同一个 state 已被消费：即使这次 code 合法也不放行
        MvcResult replay = mvc.perform(get("/api/auth/dingtalk/callback").session(session)
                .param("code", "good-code").param("state", state)).andReturn();
        assertThat(replay.getResponse().getStatus()).isEqualTo(400);
        assertThat(auditCount("DINGTALK_STATE_INVALID")).isEqualTo(1);
    }

    @Test
    void firstLoginCreatesOwnerAccountAndRedirectsToFrontend() throws Exception {
        MockHttpSession session = new MockHttpSession();
        String state = authorize(session, "/my");

        MvcResult callback = mvc.perform(get("/api/auth/dingtalk/callback").session(session)
                .param("code", "good-code").param("state", state)).andReturn();

        assertThat(callback.getResponse().getStatus()).isEqualTo(302);
        assertThat(callback.getResponse().getHeader("Location"))
                .isEqualTo("http://localhost:5173/sso/callback?next=%2Fmy");

        Map<String, Object> user = jdbc.queryForMap(
                "SELECT id, name, role, status, username, must_change_password FROM users WHERE dingtalk_union_id = ?",
                UNION_ID);
        assertThat(user.get("name")).isEqualTo("钉钉小王");
        assertThat(user.get("role")).isEqualTo("OWNER"); // 钉钉只给身份，角色默认负责人
        assertThat(user.get("status")).isEqualTo("ACTIVE");
        assertThat(user.get("username")).isNull();
        assertThat((Boolean) user.get("must_change_password")).isFalse();
        assertThat(auditCount("DINGTALK_LOGIN")).isEqualTo(1);
        assertThat(session.getAttribute(AuthSession.ATTR)).isNotNull();

        // 第二次登录复用同一账号，不重复建档
        MockHttpSession second = new MockHttpSession();
        String state2 = authorize(second, null);
        MvcResult again = mvc.perform(get("/api/auth/dingtalk/callback").session(second)
                .param("code", "good-code").param("state", state2)).andReturn();
        assertThat(again.getResponse().getHeader("Location")).isEqualTo("http://localhost:5173/sso/callback");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM users WHERE dingtalk_union_id = ?", Integer.class,
                UNION_ID)).isEqualTo(1);
    }

    @Test
    void externalNextIsDiscardedToBlockOpenRedirect() throws Exception {
        MockHttpSession session = new MockHttpSession();
        String state = authorize(session, "https://evil.example.com/steal");

        MvcResult callback = mvc.perform(get("/api/auth/dingtalk/callback").session(session)
                .param("code", "good-code").param("state", state)).andReturn();

        assertThat(callback.getResponse().getHeader("Location"))
                .isEqualTo("http://localhost:5173/sso/callback");
    }

    @Test
    void autoLoginReturnsSessionUserAndRejectsEmptyCode() throws Exception {
        MvcResult ok = mvc.perform(post("/api/auth/dingtalk/auto").with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("authCode", "good-code", "next", "/dashboard"))))
                .andReturn();
        assertThat(ok.getResponse().getStatus()).isEqualTo(200);
        JsonNode user = json.readTree(ok.getResponse().getContentAsString());
        assertThat(user.get("name").asText()).isEqualTo("钉钉小王");
        assertThat(user.get("landingPath").asText()).isEqualTo("/my");

        MvcResult empty = mvc.perform(post("/api/auth/dingtalk/auto").with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("authCode", " ")))).andReturn();
        assertThat(empty.getResponse().getStatus()).isEqualTo(422); // @NotBlank 拦截
    }

    /** 走真实接口拿授权地址，并从 URL 里取回 state（与浏览器跳转一致）。 */
    private String authorize(MockHttpSession session, String next) throws Exception {
        var builder = get("/api/auth/dingtalk/authorize-url").session(session);
        if (next != null) {
            builder.param("next", next);
        }
        MvcResult result = mvc.perform(builder).andReturn();
        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        JsonNode body = json.readTree(result.getResponse().getContentAsString());
        String url = body.get("authorizeUrl").asText();
        assertThat(url).startsWith("https://login.dingtalk.com/oauth2/auth?")
                .contains("client_id=ding-app-key")
                .contains("redirect_uri=http%3A%2F%2Flocalhost%3A5173%2Fapi%2Fauth%2Fdingtalk%2Fcallback");
        Matcher matcher = STATE.matcher(url);
        assertThat(matcher.find()).isTrue();
        return matcher.group(1);
    }

    private int auditCount(String action) {
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM audit_logs WHERE action = ?", Integer.class,
                action);
        return count == null ? 0 : count;
    }

    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json;charset=UTF-8");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }
}
