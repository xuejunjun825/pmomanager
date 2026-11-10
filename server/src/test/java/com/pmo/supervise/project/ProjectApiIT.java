package com.pmo.supervise.project;

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

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** /api/projects 五个端点与失败语义（TECHNICAL §5.1 / §6.1 / AC-1 / AC-4 / AC-8）。 */
@AutoConfigureMockMvc
class ProjectApiIT extends IntegrationTestSupport {

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

    private MockHttpSession adminSession() throws Exception {
        seedUser("prjadmin", "项目管理员", Role.ADMIN, "ACTIVE");
        return login("prjadmin");
    }

    @Test
    void createListUpdateAndDeleteRoundTrip() throws Exception {
        MockHttpSession session = adminSession();

        mvc.perform(get("/api/projects").session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", org.hamcrest.Matchers.hasSize(0)));

        MvcResult created = mvc.perform(post("/api/projects").session(session).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"智能工厂\",\"sort\":3}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.code").value(org.hamcrest.Matchers.matchesPattern("P\\d{5}")))
                .andExpect(jsonPath("$.name").value("智能工厂"))
                .andExpect(jsonPath("$.sort").value(3))
                .andExpect(jsonPath("$.enabled").value(true))
                .andExpect(jsonPath("$.usageCount").value(0))
                .andReturn();
        long first = ((Number) com.jayway.jsonpath.JsonPath.read(
                created.getResponse().getContentAsString(), "$.id")).longValue();

        // 排序缺省：排在最大值之后（3 → 4），而不是固定 0。
        mvc.perform(post("/api/projects").session(session).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"二期建设\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.sort").value(4))
                .andExpect(jsonPath("$.enabled").value(true));

        mvc.perform(get("/api/projects").session(session))
                .andExpect(jsonPath("$", org.hamcrest.Matchers.hasSize(2)))
                .andExpect(jsonPath("$[0].name").value("智能工厂"))
                .andExpect(jsonPath("$[1].name").value("二期建设"));

        mvc.perform(put("/api/projects/" + first).session(session).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"智能工厂（改）\",\"sort\":7,\"enabled\":false}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(org.hamcrest.Matchers.matchesPattern("P\\d{5}")))
                .andExpect(jsonPath("$.name").value("智能工厂（改）"))
                .andExpect(jsonPath("$.sort").value(7))
                .andExpect(jsonPath("$.enabled").value(false));

        mvc.perform(patch("/api/projects/" + first + "/status").session(session).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"enabled\":true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enabled").value(true))
                .andExpect(jsonPath("$.name").value("智能工厂（改）"));

        mvc.perform(delete("/api/projects/" + first).session(session).with(csrf()))
                .andExpect(status().isNoContent());
        mvc.perform(get("/api/projects").session(session))
                .andExpect(jsonPath("$", org.hamcrest.Matchers.hasSize(1)));
    }

    @Test
    void nameLengthAndDuplicateIncludingDisabled() throws Exception {
        MockHttpSession session = adminSession();

        mvc.perform(post("/api/projects").session(session).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"" + "甲".repeat(21) + "\"}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.fieldErrors[0].field").value("name"));

        mvc.perform(post("/api/projects").session(session).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"甲\"}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));

        mvc.perform(post("/api/projects").session(session).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"   \"}"))
                .andExpect(status().isUnprocessableEntity());

        MvcResult created = mvc.perform(post("/api/projects").session(session).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"质量项目\"}"))
                .andExpect(status().isCreated())
                .andReturn();
        long id = ((Number) com.jayway.jsonpath.JsonPath.read(
                created.getResponse().getContentAsString(), "$.id")).longValue();

        // 首尾空白先 trim 再查重：重复名 409，且落到 name 字段。
        mvc.perform(post("/api/projects").session(session).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\" 质量项目 \"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PROJECT_NAME_DUPLICATE"))
                .andExpect(jsonPath("$.fieldErrors[0].field").value("name"));

        // 停用的项目仍占名称：启用/停用只影响可选性，不影响唯一性。
        mvc.perform(patch("/api/projects/" + id + "/status").session(session).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"enabled\":false}"))
                .andExpect(status().isOk());
        mvc.perform(post("/api/projects").session(session).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"质量项目\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PROJECT_NAME_DUPLICATE"));
    }

    @Test
    void usageCountAndDeleteGuardForReferencedProject() throws Exception {
        MockHttpSession session = adminSession();
        MvcResult created = mvc.perform(post("/api/projects").session(session).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"被引用项目\"}"))
                .andExpect(status().isCreated())
                .andReturn();
        long id = ((Number) com.jayway.jsonpath.JsonPath.read(
                created.getResponse().getContentAsString(), "$.id")).longValue();

        long owner = seedOwner("owner1", "张三");
        long taskId = seedTask("绑定项目的任务", owner, LocalDate.of(2026, 11, 20), "PENDING", adminId);
        // 三态互斥：绑项目必须清掉说明（ck_tasks_source 的三臂恰好一组非空）。
        jdbc.update("UPDATE tasks SET source_project_id = ?, source_note = NULL WHERE id = ?", id, taskId);

        mvc.perform(get("/api/projects").session(session))
                .andExpect(jsonPath("$[0].usageCount").value(1));

        mvc.perform(delete("/api/projects/" + id).session(session).with(csrf()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PROJECT_IN_USE"))
                .andExpect(jsonPath("$.details.taskCount").value(1))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("1 条任务")));

        // 被引用也能停用：停用是替代删除的唯一手段（AC-4）。
        mvc.perform(patch("/api/projects/" + id + "/status").session(session).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"enabled\":false}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.usageCount").value(1));

        jdbc.update("DELETE FROM tasks WHERE id = ?", taskId);
        mvc.perform(delete("/api/projects/" + id).session(session).with(csrf()))
                .andExpect(status().isNoContent());
    }

    @Test
    void twoHundredProjectLimitIsEnforced() throws Exception {
        for (int i = 1; i <= 200; i++) {
            jdbc.update("INSERT INTO projects (code, name, sort_no, enabled) VALUES (?, ?, 0, 0)",
                    String.format("PB%05d", i), String.format("批量项目%03d", i));
        }
        MockHttpSession session = adminSession();

        mvc.perform(post("/api/projects").session(session).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"第 201 个\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PROJECT_LIMIT_REACHED"));
    }

    @Test
    void unknownProjectAndBadSort() throws Exception {
        MockHttpSession session = adminSession();

        mvc.perform(put("/api/projects/999999").session(session).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"不存在\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("PROJECT_INVALID"));

        mvc.perform(patch("/api/projects/999999/status").session(session).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"enabled\":false}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("PROJECT_INVALID"));

        mvc.perform(delete("/api/projects/999999").session(session).with(csrf()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("PROJECT_INVALID"));

        // 排序越界按 0–99 截断，不报错（配置页只有 0–99 的输入框）。
        MvcResult created = mvc.perform(post("/api/projects").session(session).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"截断排序\",\"sort\":123}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.sort").value(99))
                .andReturn();
        assertThat(created.getResponse().getContentAsString()).contains("\"sort\":99");
    }

    @Test
    void nonAdminAndAnonymousAreRejected() throws Exception {
        seedOwner("owner1", "张三");
        MockHttpSession session = login("owner1");

        mvc.perform(get("/api/projects").session(session))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/projects").session(session).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"越权项目\"}"))
                .andExpect(status().isForbidden());

        mvc.perform(get("/api/projects"))
                .andExpect(status().isUnauthorized());
    }
}
