package com.pmo.supervise.meta;

import com.pmo.supervise.IntegrationTestSupport;
import com.pmo.supervise.common.Priority;
import com.pmo.supervise.common.Role;
import com.pmo.supervise.common.TaskStatus;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.time.LocalDate;

import static org.hamcrest.Matchers.everyItem;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasItems;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * GET /api/meta/options 的 HTTP 契约（D-01 门禁）：
 * 登录可用、五个下拉齐全、OWNER 只见本人与本人任务涉及的会议。
 */
@AutoConfigureMockMvc
class MetaApiIT extends IntegrationTestSupport {

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
    void adminGetsEveryDropdown() throws Exception {
        long admin = seedUser("metaadmin", "Meta 管理员", Role.ADMIN, "ACTIVE");
        long owner = seedOwner("metaowner", "Meta 负责人");
        long disabled = seedUser("metadisabled", "Meta 停用", Role.OWNER, "DISABLED");
        long meeting = seedMeeting("Meta 会议", admin);
        seedTask("Meta 任务", owner, LocalDate.of(2026, 11, 20), "PENDING", admin);

        MockHttpSession session = login("metaadmin");

        mvc.perform(get("/api/meta/options").session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.owners[*].status", everyItem(is("ACTIVE"))))
                .andExpect(jsonPath("$.owners[*].id", hasItem((int) owner)))
                .andExpect(jsonPath("$.owners[*].id", not(hasItem((int) disabled))))
                .andExpect(jsonPath("$.meetings", hasSize(1)))
                .andExpect(jsonPath("$.meetings[0].id").value(meeting))
                .andExpect(jsonPath("$.meetings[0].title").value("Meta 会议"))
                .andExpect(jsonPath("$.meetings[0].meetingDate").value("2026-11-01"))
                // 来源类型是管理员可维护的主数据，数量会随配置变；按编码取而不是按位置断言（AC-5）
                .andExpect(jsonPath("$.sourceTypes[*].value", hasItems("MEETING", "PROJECT", "DAILY")))
                .andExpect(jsonPath("$.sourceTypes[?(@.value == 'MEETING')].label", hasItem("会议决议")))
                .andExpect(jsonPath("$.sourceTypes[?(@.value == 'MEETING')].color", hasItem("#1B4D8F")))
                .andExpect(jsonPath("$.sourceTypes[?(@.value == 'MEETING')].form", hasItem("MEETING")))
                .andExpect(jsonPath("$.sourceTypes[?(@.value == 'MEETING')].enabled", hasItem(true)))
                .andExpect(jsonPath("$.statuses", hasSize(TaskStatus.values().length)))
                .andExpect(jsonPath("$.statuses[0].value").value(TaskStatus.values()[0].name()))
                .andExpect(jsonPath("$.priorities", hasSize(Priority.values().length)))
                .andExpect(jsonPath("$.priorities[0].label").value(Priority.values()[0].label()));
    }

    @Test
    void ownerOnlySeesSelfAndMeetingsOfOwnTasks() throws Exception {
        long admin = seedUser("metaadmin", "Meta 管理员", Role.ADMIN, "ACTIVE");
        long owner = seedOwner("metaowner", "Meta 负责人");
        long otherMeeting = seedMeeting("他人会议", admin);
        long ownMeeting = seedMeeting("本人会议", admin);
        long decision = seedDecisionItem(ownMeeting, "本人决议", owner);
        seedMeetingTask("本人会议任务", owner, LocalDate.of(2026, 11, 20), "PENDING", admin,
                ownMeeting, decision);

        MockHttpSession session = login("metaowner");

        mvc.perform(get("/api/meta/options").session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.owners", hasSize(1)))
                .andExpect(jsonPath("$.owners[0].id").value(owner))
                .andExpect(jsonPath("$.meetings", hasSize(1)))
                .andExpect(jsonPath("$.meetings[0].id").value(ownMeeting))
                .andExpect(jsonPath("$.meetings[*].id", not(hasItem((int) otherMeeting))));
    }

    /**
     * 项目不是权限维度（AC-8）：ADMIN 与 OWNER 拿到同一份项目下拉，含停用项（筛选仍要能选中历史项目）。
     * 契约只暴露 id/name/enabled/sort —— 编码与引用计数是管理接口的信息，不能漏进全员可读的 meta。
     */
    @Test
    void projectsAreIdenticalForEveryRoleAndExposeOnlyFourFields() throws Exception {
        seedUser("metaadmin", "Meta 管理员", Role.ADMIN, "ACTIVE");
        seedOwner("metaowner", "Meta 负责人");
        seedProject("P00001", "智能工厂", 1, true);
        seedProject("P00002", "已停用项目", 2, false);
        seedProject("P00003", "城市更新一期", 3, true);

        MockHttpSession adminSession = login("metaadmin");
        mvc.perform(get("/api/meta/options").session(adminSession))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.projects", hasSize(3)))
                .andExpect(jsonPath("$.projects[0].name").value("智能工厂"))
                .andExpect(jsonPath("$.projects[1].name").value("已停用项目"))
                .andExpect(jsonPath("$.projects[2].name").value("城市更新一期"))
                .andExpect(jsonPath("$.projects[1].enabled").value(false))
                .andExpect(jsonPath("$.projects[0].sort").value(1))
                .andExpect(jsonPath("$.projects[0].id").isNumber())
                // 管理视图独有的两个字段绝不下发（AC-8 / 反面目标：meta 泄露编码与引用计数）
                .andExpect(jsonPath("$.projects[0].code").doesNotExist())
                .andExpect(jsonPath("$.projects[0].usageCount").doesNotExist())
                .andExpect(jsonPath("$.projects[1].code").doesNotExist())
                .andExpect(jsonPath("$.projects[1].usageCount").doesNotExist());

        MockHttpSession ownerSession = login("metaowner");
        mvc.perform(get("/api/meta/options").session(ownerSession))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.projects", hasSize(3)))
                .andExpect(jsonPath("$.projects[*].name", hasItems("智能工厂", "已停用项目", "城市更新一期")))
                .andExpect(jsonPath("$.projects[0].code").doesNotExist())
                .andExpect(jsonPath("$.projects[0].usageCount").doesNotExist());
    }

    private void seedProject(String code, String name, int sort, boolean enabled) {
        jdbc.update("INSERT INTO projects (code, name, sort_no, enabled) VALUES (?, ?, ?, ?)",
                code, name, sort, enabled ? 1 : 0);
    }
}
