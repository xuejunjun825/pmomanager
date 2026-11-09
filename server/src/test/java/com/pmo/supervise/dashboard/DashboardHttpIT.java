package com.pmo.supervise.dashboard;

import com.pmo.supervise.IntegrationTestSupport;
import com.pmo.supervise.common.ShanghaiClock;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.time.LocalDate;

import static org.hamcrest.Matchers.nullValue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 矩阵/看板的 HTTP 契约（TECHNICAL §5.5）：真实会话 + 过滤器链 + Actor 解析 + JSON 形状。
 * 登录用独立 ADMIN 账号，避免与 AuthApiTest 对 admin 改密的用例互相影响。
 */
@AutoConfigureMockMvc
class DashboardHttpIT extends IntegrationTestSupport {

    private static final String PASSWORD = "Admin@12345";

    @Autowired
    private MockMvc mvc;

    @Autowired
    private ShanghaiClock clock;

    private MockHttpSession login(String username) throws Exception {
        MvcResult result = mvc.perform(post("/api/auth/login").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + username + "\",\"password\":\"" + PASSWORD + "\"}"))
                .andExpect(status().isOk())
                .andReturn();
        return (MockHttpSession) result.getRequest().getSession(false);
    }

    @Test
    void matrixAndDashboardAreServedOverHttpWithServerSideNumbers() throws Exception {
        long admin = seedUser("httpadmin", "HTTP 管理员", com.pmo.supervise.common.Role.ADMIN, "ACTIVE");
        long owner = seedOwner("httpowner", "HTTP 负责人");
        LocalDate today = clock.today();
        seedTask("HTTP 逾期", owner, today.minusDays(1), "PENDING", admin);
        seedTask("HTTP 今天到期", owner, today, "PENDING", admin);
        seedTask("HTTP 已完成", owner, today.minusDays(5), "DONE", admin);
        seedTask("HTTP 已取消", owner, today.minusDays(6), "CANCELLED", admin);

        MockHttpSession session = login("httpadmin");

        mvc.perform(get("/api/matrix").param("range", "ALL").session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.today").value(today.toString()))
                .andExpect(jsonPath("$.rangeFrom").value(nullValue()))
                .andExpect(jsonPath("$.rows[0].name").value("HTTP 负责人"))
                .andExpect(jsonPath("$.rows[0].pending").value(1))
                .andExpect(jsonPath("$.rows[0].overdue").value(1))
                .andExpect(jsonPath("$.rows[0].done").value(1))
                .andExpect(jsonPath("$.rows[0].cancelled").value(1))
                .andExpect(jsonPath("$.rows[0].total").value(3))
                .andExpect(jsonPath("$.totals.userId").value(0))
                .andExpect(jsonPath("$.totals.name").value("合计"))
                .andExpect(jsonPath("$.totals.total").value(3));

        mvc.perform(get("/api/dashboard").param("focusLimit", "10").session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.kpis.total").value(3))
                .andExpect(jsonPath("$.kpis.unfinished").value(2))
                .andExpect(jsonPath("$.kpis.overdue").value(1))
                .andExpect(jsonPath("$.kpis.overdueRate").value(0.5))
                .andExpect(jsonPath("$.kpis.dueSoon24h").value(1))
                .andExpect(jsonPath("$.focusTotal").value(2))
                .andExpect(jsonPath("$.focus[0].title").value("HTTP 逾期"))
                .andExpect(jsonPath("$.focus[0].isOverdue").value(true))
                .andExpect(jsonPath("$.focus[0].overdueDays").value(1))
                .andExpect(jsonPath("$.focus[1].title").value("HTTP 今天到期"));
    }

    @Test
    void ownerCannotReadManagementViews() throws Exception {
        long admin = seedUser("httpadmin", "HTTP 管理员", com.pmo.supervise.common.Role.ADMIN, "ACTIVE");
        long owner = seedOwner("httpowner", "HTTP 负责人");
        seedTask("HTTP 逾期", owner, clock.today().minusDays(1), "PENDING", admin);

        MockHttpSession session = login("httpowner");
        mvc.perform(get("/api/matrix").session(session))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
        mvc.perform(get("/api/dashboard").session(session))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
    }

    @Test
    void anonymousIsAskedToLogin() throws Exception {
        mvc.perform(get("/api/dashboard"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTH_REQUIRED"));
    }
}
