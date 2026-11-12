package com.pmo.supervise.gantt;

import com.pmo.supervise.IntegrationTestSupport;
import com.pmo.supervise.common.Role;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.time.LocalDate;

import static org.hamcrest.Matchers.hasSize;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 甘特边界与入口权限（TECHNICAL §3.4 / §6.2 / AC-12）：
 * from=to 的单日窗口、400 天上限、起止倒置、日期格式非法、缺参、空窗口 200 + 空数组、OWNER 403。
 */
@AutoConfigureMockMvc
class GanttWindowBoundaryTest extends IntegrationTestSupport {

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
    void singleDayEmptyWindowReturns200WithEmptyGroups() throws Exception {
        MockHttpSession session = login("admin");

        mvc.perform(get("/api/gantt").session(session)
                        .param("from", "2026-11-12")
                        .param("to", "2026-11-12"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.from").value("2026-11-12"))
                .andExpect(jsonPath("$.to").value("2026-11-12"))
                .andExpect(jsonPath("$.truncated").value(false))
                .andExpect(jsonPath("$.windowTotal").value(0))
                .andExpect(jsonPath("$.groups", hasSize(0)))
                .andExpect(jsonPath("$.summary.overdue").value(0))
                .andExpect(jsonPath("$.summary.risk").value(0));
    }

    @Test
    void exactlyFourHundredDaysIsAllowed() throws Exception {
        MockHttpSession session = login("admin");

        mvc.perform(get("/api/gantt").session(session)
                        .param("from", "2025-11-09")
                        .param("to", "2026-12-13"))
                .andExpect(status().isOk());
    }

    @Test
    void windowWiderThanFourHundredDaysIsRejected() throws Exception {
        MockHttpSession session = login("admin");

        mvc.perform(get("/api/gantt").session(session)
                        .param("from", "2025-11-09")
                        .param("to", "2026-12-14"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.fieldErrors[0].field").value("to"));
    }

    @Test
    void reversedWindowIsRejected() throws Exception {
        MockHttpSession session = login("admin");

        mvc.perform(get("/api/gantt").session(session)
                        .param("from", "2026-12-01")
                        .param("to", "2026-11-01"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.fieldErrors[0].field").value("from"));
    }

    @Test
    void malformedOrMissingDatesAreBadRequest() throws Exception {
        MockHttpSession session = login("admin");

        mvc.perform(get("/api/gantt").session(session)
                        .param("from", "2026-13-01")
                        .param("to", "2026-11-30"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));

        mvc.perform(get("/api/gantt").session(session)
                        .param("from", "2026-11-01"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void taskFieldsMatchTheFrontendContract() throws Exception {
        long owner = seedUser("gantt-json-owner", "契约负责人", Role.OWNER, "ACTIVE");
        long taskId = seedTask("契约任务", owner, LocalDate.of(2026, 11, 1), "PENDING", adminId);
        // 创建日放在窗口左侧之外：起点无计划日时会回退到创建日并 pin 左端。
        jdbc.update("UPDATE tasks SET created_at = '2025-06-01 09:00:00' WHERE id = ?", taskId);
        MockHttpSession session = login("admin");

        mvc.perform(get("/api/gantt").session(session)
                        .param("from", "2026-11-01")
                        .param("to", "2026-11-30"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.groups[0].unassigned").value(true))
                .andExpect(jsonPath("$.groups[0].themeName").value("未归主题"))
                .andExpect(jsonPath("$.groups[0].projects[0].unspecified").value(true))
                .andExpect(jsonPath("$.groups[0].projects[0].tasks[0].id").isNumber())
                .andExpect(jsonPath("$.groups[0].projects[0].tasks[0].status").value("PENDING"))
                .andExpect(jsonPath("$.groups[0].projects[0].tasks[0].statusLabel").isNotEmpty())
                // 前端 GanttTask 用 isOverdue / isAtRisk 两个字段名，序列化后必须逐字对得上。
                .andExpect(jsonPath("$.groups[0].projects[0].tasks[0].isOverdue").value(true))
                .andExpect(jsonPath("$.groups[0].projects[0].tasks[0].isAtRisk").value(true))
                .andExpect(jsonPath("$.groups[0].projects[0].tasks[0].estimatedStart").value(true))
                .andExpect(jsonPath("$.groups[0].projects[0].tasks[0].pinnedStart").value(true))
                .andExpect(jsonPath("$.groups[0].projects[0].tasks[0].pinnedEnd").value(false))
                .andExpect(jsonPath("$.groups[0].projects[0].tasks[0].plannedStartDate").doesNotExist())
                .andExpect(jsonPath("$.groups[0].projects[0].tasks[0].startDate").value("2025-06-01"))
                .andExpect(jsonPath("$.groups[0].projects[0].tasks[0].sourceLabel").isNotEmpty())
                .andExpect(jsonPath("$.summary.unassigned").value(1));
    }

    @Test
    void ownerRoleIsForbidden() throws Exception {
        seedOwner("gantt-http-owner", "无权负责人");
        MockHttpSession session = login("gantt-http-owner");

        mvc.perform(get("/api/gantt").session(session)
                        .param("from", "2026-11-01")
                        .param("to", "2026-11-30"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
    }
}
