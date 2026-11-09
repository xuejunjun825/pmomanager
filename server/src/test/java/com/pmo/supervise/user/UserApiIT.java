package com.pmo.supervise.user;

import com.pmo.supervise.IntegrationTestSupport;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.sql.Timestamp;
import java.time.LocalDateTime;

import static org.hamcrest.Matchers.hasSize;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 用户列表契约（D-03 门禁）：GET /api/users 必须把 users.last_login_at 作为 lastLoginAt 返回，
 * 前端「最近登录」列才有真实值。
 */
@AutoConfigureMockMvc
class UserApiIT extends IntegrationTestSupport {

    private static final String PASSWORD = "Admin@12345";

    @Autowired
    private MockMvc mvc;

    private MockHttpSession login(String username) throws Exception {
        MvcResult result = mvc.perform(post("/api/auth/login").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + username + "\",\"password\":\"" + PASSWORD + "\"}"))
                .andExpect(status().isOk())
                .andReturn();
        return (MockHttpSession) result.getRequest().getSession(false);
    }

    @Test
    void userListExposesLastLoginAt() throws Exception {
        seedUser("lastloginadmin", "登录管理员", com.pmo.supervise.common.Role.ADMIN, "ACTIVE");
        long owner = seedOwner("lastloginowner", "登录负责人");
        LocalDateTime lastLogin = LocalDateTime.of(2026, 11, 8, 10, 15, 30);
        jdbc.update("UPDATE users SET last_login_at = ? WHERE id = ?", Timestamp.valueOf(lastLogin), owner);

        mvc.perform(get("/api/users").param("keyword", "lastloginowner").session(login("lastloginadmin")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items", hasSize(1)))
                .andExpect(jsonPath("$.items[0].lastLoginAt").value("2026-11-08T10:15:30"));
    }
}
