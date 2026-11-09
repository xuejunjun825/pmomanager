package com.pmo.supervise.auth;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pmo.supervise.IntegrationTestSupport;
import com.pmo.supervise.common.AuthSession;
import com.pmo.supervise.common.Role;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.time.Duration;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 认证与会话（AC-1 / AC-18）：统一失败文案、停用 403、7 天会话、登出即失效、首登强制改密、
 * 钉钉未配置降级。
 */
@AutoConfigureMockMvc
class AuthApiTest extends IntegrationTestSupport {

    /** 响应体按 UTF-8 解码：中文错误文案与姓名才不会被当成 ISO-8859-1 读成乱码。 */
    @org.springframework.boot.test.context.TestConfiguration
    static class Utf8Responses {
        @org.springframework.context.annotation.Bean
        org.springframework.boot.test.autoconfigure.web.servlet.MockMvcBuilderCustomizer utf8() {
            return builder -> builder.defaultResponseCharacterEncoding(java.nio.charset.StandardCharsets.UTF_8);
        }
    }

    private static final String SEED_PASSWORD = "Admin@12345";

    @Autowired
    private MockMvc mvc;

    @Autowired
    private ObjectMapper json;

    @Value("${server.servlet.session.timeout}")
    private Duration sessionTimeout;

    @BeforeEach
    void normalizeAdmin() {
        // 种子 admin 的 must_change_password=1 会让所有管理请求 409；认证用例只关心业务规则。
        jdbc.update("UPDATE users SET must_change_password = 0, status = 'ACTIVE', role = 'ADMIN' WHERE id = ?",
                adminId);
    }

    @Test
    void wrongPasswordAndUnknownAccountShareTheSameRejection() throws Exception {
        seedOwner("zhang", "张三");

        MvcResult wrong = mvc.perform(post("/api/auth/login").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(Map.of("username", "zhang", "password", "WrongPass1"))))
                .andReturn();
        MvcResult unknown = mvc.perform(post("/api/auth/login").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(Map.of("username", "nobody", "password", "WrongPass1"))))
                .andReturn();

        assertThat(wrong.getResponse().getStatus()).isEqualTo(401);
        assertThat(unknown.getResponse().getStatus()).isEqualTo(401);
        JsonNode a = json.readTree(wrong.getResponse().getContentAsString());
        JsonNode b = json.readTree(unknown.getResponse().getContentAsString());
        assertThat(a.get("code").asText()).isEqualTo("AUTH_BAD_CREDENTIALS");
        assertThat(b.get("code").asText()).isEqualTo("AUTH_BAD_CREDENTIALS");
        assertThat(a.get("message").asText()).isEqualTo("账号或密码不正确");
        assertThat(b.get("message").asText()).isEqualTo(a.get("message").asText());
        // 两次失败都必须留痕（AC-18）
        assertThat(auditCount("LOGIN_FAILED")).isEqualTo(2);
    }

    @Test
    void disabledAccountIsRejectedWith403AfterPasswordCheck() throws Exception {
        seedUser("li", "李四", Role.OWNER, "DISABLED");

        MvcResult result = mvc.perform(post("/api/auth/login").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(Map.of("username", "li", "password", SEED_PASSWORD))))
                .andReturn();

        assertThat(result.getResponse().getStatus()).isEqualTo(403);
        assertThat(json.readTree(result.getResponse().getContentAsString()).get("code").asText())
                .isEqualTo("AUTH_DISABLED");
        assertThat(auditCount("LOGIN_DISABLED")).isEqualTo(1);
    }

    @Test
    void loginEstablishesSevenDaySessionAndLogoutInvalidatesIt() throws Exception {
        long ownerId = seedOwner("wang", "王五");

        MvcResult login = mvc.perform(post("/api/auth/login").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(Map.of("username", "wang", "password", SEED_PASSWORD))))
                .andExpect(status().isOk())
                .andReturn();

        JsonNode user = json.readTree(login.getResponse().getContentAsString());
        assertThat(user.get("id").asLong()).isEqualTo(ownerId);
        assertThat(user.get("role").asText()).isEqualTo("OWNER");
        assertThat(user.get("landingPath").asText()).isEqualTo("/my");
        assertThat(user.get("mustChangePassword").asBoolean()).isFalse();

        MockHttpSession session = (MockHttpSession) login.getRequest().getSession(false);
        assertThat(session).isNotNull();
        assertThat(session.getAttribute(AuthSession.ATTR)).isNotNull();
        assertThat(sessionTimeout).isEqualTo(Duration.ofDays(7));

        mvc.perform(get("/api/auth/me").session(session))
                .andExpect(status().isOk());

        mvc.perform(post("/api/auth/logout").with(csrf()).session(session))
                .andExpect(status().isNoContent());
        assertThat(session.isInvalid()).isTrue();
        assertThat(auditCount("LOGOUT")).isEqualTo(1);
    }

    @Test
    void mustChangePasswordBlocksEverythingExceptAuthEndpoints() throws Exception {
        long ownerId = seedOwner("zhao", "赵六");
        jdbc.update("UPDATE users SET must_change_password = 1 WHERE id = ?", ownerId);

        MvcResult login = mvc.perform(post("/api/auth/login").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(Map.of("username", "zhao", "password", SEED_PASSWORD))))
                .andReturn();
        assertThat(login.getResponse().getStatus()).isEqualTo(200);
        assertThat(json.readTree(login.getResponse().getContentAsString())
                .get("mustChangePassword").asBoolean()).isTrue();
        MockHttpSession session = (MockHttpSession) login.getRequest().getSession(false);

        // 业务接口被闸门挡下
        MvcResult blocked = mvc.perform(get("/api/users").session(session)).andReturn();
        assertThat(blocked.getResponse().getStatus()).isEqualTo(409);
        assertThat(json.readTree(blocked.getResponse().getContentAsString()).get("code").asText())
                .isEqualTo("PASSWORD_CHANGE_REQUIRED");

        // 取当前用户不受限
        mvc.perform(get("/api/auth/me").session(session))
                .andExpect(status().isOk());

        // 弱口令 422
        MvcResult weak = mvc.perform(post("/api/auth/password").with(csrf()).session(session)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(Map.of("oldPassword", SEED_PASSWORD, "newPassword", "short1"))))
                .andReturn();
        assertThat(weak.getResponse().getStatus()).isEqualTo(422);
        assertThat(json.readTree(weak.getResponse().getContentAsString()).get("code").asText())
                .isEqualTo("AUTH_PASSWORD_WEAK");

        // 原密码错误 400
        MvcResult wrongOld = mvc.perform(post("/api/auth/password").with(csrf()).session(session)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(Map.of("oldPassword", "Nope12345", "newPassword", "NewPass123"))))
                .andReturn();
        assertThat(wrongOld.getResponse().getStatus()).isEqualTo(400);
        assertThat(json.readTree(wrongOld.getResponse().getContentAsString()).get("code").asText())
                .isEqualTo("AUTH_OLD_PASSWORD_WRONG");
        assertThat(auditCount("PASSWORD_CHANGE_REJECTED")).isEqualTo(1);

        // 改密成功 → 闸门解除
        mvc.perform(post("/api/auth/password").with(csrf()).session(session)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(Map.of("oldPassword", SEED_PASSWORD, "newPassword", "NewPass123"))))
                .andExpect(status().isNoContent());
        MvcResult after = mvc.perform(get("/api/users").session(session)).andReturn();
        assertThat(after.getResponse().getStatus()).isEqualTo(403); // 非管理员，但已不是 409
        assertThat(jdbc.queryForObject("SELECT must_change_password FROM users WHERE id = ?", Boolean.class,
                ownerId)).isFalse();

        // 新密码可登录
        mvc.perform(post("/api/auth/login").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(Map.of("username", "zhao", "password", "NewPass123"))))
                .andExpect(status().isOk());
    }

    @Test
    void userManagementIsAdminOnlyAndExposesAccountRows() throws Exception {
        long ownerId = seedOwner("chen", "陈七");
        long execId = seedExec("sun", "孙八");
        MockHttpSession admin = sessionOf(adminId);

        // 负责人 / 管理层一律 403（AC-2 / AC-18）
        assertThat(statusOf(get("/api/users").session(sessionOf(ownerId)))).isEqualTo(403);
        assertThat(statusOf(get("/api/users").session(sessionOf(execId)))).isEqualTo(403);
        assertThat(statusOf(patch("/api/users/" + ownerId + "/role").with(csrf()).session(sessionOf(ownerId))
                .contentType(MediaType.APPLICATION_JSON).content(body(Map.of("role", "ADMIN"))))).isEqualTo(403);

        MvcResult page = mvc.perform(get("/api/users").param("keyword", "陈").session(admin)).andReturn();
        assertThat(page.getResponse().getStatus()).isEqualTo(200);
        JsonNode row = json.readTree(page.getResponse().getContentAsString()).get("items").get(0);
        assertThat(row.get("username").asText()).isEqualTo("chen");
        assertThat(row.get("name").asText()).isEqualTo("陈七");
        assertThat(row.get("role").asText()).isEqualTo("OWNER");
        assertThat(row.get("status").asText()).isEqualTo("ACTIVE");
        assertThat(row.get("dingtalkBound").asBoolean()).isFalse();
        assertThat(row.has("passwordHash")).isFalse();

        // 下拉数据源对登录用户开放（任务表单需要选人）
        assertThat(statusOf(get("/api/users/options").session(sessionOf(ownerId)))).isEqualTo(200);

        // 角色/状态变更留痕
        MvcResult roleChanged = mvc.perform(patch("/api/users/" + ownerId + "/role").with(csrf()).session(admin)
                .contentType(MediaType.APPLICATION_JSON).content(body(Map.of("role", "EXEC")))).andReturn();
        assertThat(roleChanged.getResponse().getStatus()).isEqualTo(200);
        assertThat(json.readTree(roleChanged.getResponse().getContentAsString()).get("role").asText())
                .isEqualTo("EXEC");
        assertThat(auditCount("USER_ROLE_CHANGED")).isEqualTo(1);

        assertThat(statusOf(patch("/api/users/" + ownerId + "/status").with(csrf()).session(admin)
                .contentType(MediaType.APPLICATION_JSON).content(body(Map.of("status", "DISABLED"))))).isEqualTo(200);
        assertThat(auditCount("USER_STATUS_CHANGED")).isEqualTo(1);

        // 停用自己 → 409
        MvcResult self = mvc.perform(patch("/api/users/" + adminId + "/status").with(csrf()).session(admin)
                .contentType(MediaType.APPLICATION_JSON).content(body(Map.of("status", "DISABLED")))).andReturn();
        assertThat(self.getResponse().getStatus()).isEqualTo(409);
        assertThat(json.readTree(self.getResponse().getContentAsString()).get("code").asText())
                .isEqualTo("SELF_DISABLE_FORBIDDEN");

        // 重置密码：12 位临时口令 + 强制改密 + 可用它登录
        long resetId = seedOwner("qian", "钱九");
        MvcResult reset = mvc.perform(post("/api/users/" + resetId + "/password-reset").with(csrf()).session(admin))
                .andReturn();
        assertThat(reset.getResponse().getStatus()).isEqualTo(200);
        String temporary = json.readTree(reset.getResponse().getContentAsString())
                .get("temporaryPassword").asText();
        assertThat(temporary).hasSize(12).matches("[A-Za-z0-9]{12}");
        assertThat(temporary).containsPattern("[A-Za-z]");
        assertThat(temporary).containsPattern("[0-9]");
        assertThat(jdbc.queryForObject("SELECT must_change_password FROM users WHERE id = ?", Boolean.class,
                resetId)).isTrue();
        assertThat(auditCount("PASSWORD_RESET")).isEqualTo(1);

        MvcResult login = mvc.perform(post("/api/auth/login").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(Map.of("username", "qian", "password", temporary))))
                .andReturn();
        assertThat(login.getResponse().getStatus()).isEqualTo(200);
        assertThat(json.readTree(login.getResponse().getContentAsString())
                .get("mustChangePassword").asBoolean()).isTrue();
    }

    @Test
    void dingTalkLoginDegradesTo503WhenUnconfigured() throws Exception {
        JsonNode config = json.readTree(mvc.perform(get("/api/auth/config"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString());
        assertThat(config.get("dingtalkConfigured").asBoolean()).isFalse();
        assertThat(config.get("passwordLogin").asBoolean()).isTrue();
        assertThat(config.get("missingKeys").size()).isEqualTo(4);

        MvcResult url = mvc.perform(get("/api/auth/dingtalk/authorize-url")
                .param("next", "/my").session(sessionOf(adminId))).andReturn();
        assertThat(url.getResponse().getStatus()).isEqualTo(503);
        assertThat(json.readTree(url.getResponse().getContentAsString()).get("code").asText())
                .isEqualTo("DINGTALK_NOT_CONFIGURED");

        MvcResult auto = mvc.perform(post("/api/auth/dingtalk/auto").with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content(body(Map.of("authCode", "whatever")))).andReturn();
        assertThat(auto.getResponse().getStatus()).isEqualTo(503);
        assertThat(auditCount("DINGTALK_NOT_CONFIGURED")).isEqualTo(2);
    }

    private MockHttpSession sessionOf(long userId) {
        MockHttpSession session = new MockHttpSession();
        session.setAttribute(AuthSession.ATTR, actor(userId));
        return session;
    }

    private int statusOf(org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder builder)
            throws Exception {
        return mvc.perform(builder).andReturn().getResponse().getStatus();
    }

    private String body(Map<String, ?> payload) throws Exception {
        return json.writeValueAsString(payload);
    }

    private int auditCount(String action) {
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM audit_logs WHERE action = ?", Integer.class,
                action);
        return count == null ? 0 : count;
    }
}
