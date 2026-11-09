package com.pmo.supervise.meta;

import com.pmo.supervise.IntegrationTestSupport;
import com.pmo.supervise.common.Priority;
import com.pmo.supervise.common.Role;
import com.pmo.supervise.common.SourceType;
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
                .andExpect(jsonPath("$.sourceTypes", hasSize(SourceType.values().length)))
                .andExpect(jsonPath("$.sourceTypes[0].value").value(SourceType.values()[0].name()))
                .andExpect(jsonPath("$.sourceTypes[0].label").value(SourceType.values()[0].label()))
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
}
