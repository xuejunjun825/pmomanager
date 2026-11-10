package com.pmo.supervise.source;

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

/** /api/source-types 六个端点与失败语义（TECHNICAL §5.1 / AC-1 / AC-6）。 */
@AutoConfigureMockMvc
class SourceTypeApiIT extends IntegrationTestSupport {

    /** source_types 是配置数据，基座不清表；这里恢复成三条内置全启用，避免用例互相污染。 */
    @org.junit.jupiter.api.BeforeEach
    void resetSources() {
        jdbc.update("DELETE FROM source_types WHERE code NOT IN ('MEETING', 'PROJECT', 'DAILY')");
        jdbc.update("UPDATE source_types SET enabled = 1");
    }

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

    /** 种一个已知口令的管理员：V2 的 admin 口令哈希与测试口令不同。 */
    private MockHttpSession adminSession() throws Exception {
        seedUser("srcadmin", "来源管理员", Role.ADMIN, "ACTIVE");
        return login("srcadmin");
    }

    @Test
    void adminListsBuiltinSourcesWithUsage() throws Exception {
        long owner = seedOwner("owner1", "张三");
        seedTask("项目任务", owner, LocalDate.of(2026, 11, 20), "PENDING", adminId);
        MockHttpSession session = adminSession();

        mvc.perform(get("/api/source-types").session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", org.hamcrest.Matchers.hasSize(3)))
                .andExpect(jsonPath("$[0].code").value("MEETING"))
                .andExpect(jsonPath("$[0].name").value("会议决议"))
                .andExpect(jsonPath("$[0].form").value("MEETING"))
                .andExpect(jsonPath("$[0].color").value("#1B4D8F"))
                .andExpect(jsonPath("$[0].softColor").value("#EAF1FA"))
                .andExpect(jsonPath("$[0].borderColor").value("#C7D9EF"))
                .andExpect(jsonPath("$[0].locked").value(true))
                .andExpect(jsonPath("$[0].builtin").value(true))
                .andExpect(jsonPath("$[0].usageCount").value(0))
                .andExpect(jsonPath("$[1].code").value("PROJECT"))
                .andExpect(jsonPath("$[1].usageCount").value(1));
    }

    @Test
    void paletteEndpointIsAvailableToAnySignedInUser() throws Exception {
        seedOwner("owner1", "张三");
        MockHttpSession session = login("owner1");

        mvc.perform(get("/api/source-types/palette").session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", org.hamcrest.Matchers.hasSize(8)))
                .andExpect(jsonPath("$[6].color").value("#475467"));
    }

    @Test
    void createUpdateDisableAndDeleteRoundTrip() throws Exception {
        MockHttpSession session = adminSession();

        MvcResult created = mvc.perform(post("/api/source-types").session(session).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"质量检查\",\"form\":\"NOTE\",\"color\":\"#067647\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.code").value(org.hamcrest.Matchers.matchesPattern("S\\d{5}")))
                .andExpect(jsonPath("$.sort").value(3))
                .andExpect(jsonPath("$.enabled").value(true))
                .andReturn();
        String code = com.jayway.jsonpath.JsonPath.read(
                created.getResponse().getContentAsString(), "$.code");

        mvc.perform(put("/api/source-types/" + code).session(session).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"质量抽查\",\"form\":\"NOTE\",\"color\":\"#3538CD\",\"sort\":5}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("质量抽查"))
                .andExpect(jsonPath("$.color").value("#3538CD"))
                .andExpect(jsonPath("$.sort").value(5));

        mvc.perform(patch("/api/source-types/" + code + "/status").session(session).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"enabled\":false}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enabled").value(false));

        mvc.perform(delete("/api/source-types/" + code).session(session).with(csrf()))
                .andExpect(status().isNoContent());

        mvc.perform(get("/api/source-types").session(session))
                .andExpect(jsonPath("$", org.hamcrest.Matchers.hasSize(3)));
    }

    @Test
    void duplicateNameAndBadFieldsAreRejected() throws Exception {
        MockHttpSession session = adminSession();

        mvc.perform(post("/api/source-types").session(session).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"项目任务\",\"form\":\"NOTE\",\"color\":\"#067647\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("SOURCE_NAME_DUPLICATE"));

        mvc.perform(post("/api/source-types").session(session).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"质\",\"form\":\"NOTE\",\"color\":\"#067647\"}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));

        mvc.perform(post("/api/source-types").session(session).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"质量检查\",\"form\":\"NOTE\",\"color\":\"#123456\"}"))
                .andExpect(status().isUnprocessableEntity());
    }

    @Test
    void builtinSourceCannotBeDeletedOrDisabled() throws Exception {
        MockHttpSession session = adminSession();

        mvc.perform(delete("/api/source-types/MEETING").session(session).with(csrf()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("SOURCE_BUILTIN_PROTECTED"));

        mvc.perform(patch("/api/source-types/MEETING/status").session(session).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"enabled\":false}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("SOURCE_BUILTIN_PROTECTED"));
    }

    @Test
    void referencedSourceDeleteSaysInUse() throws Exception {
        long owner = seedOwner("owner1", "张三");
        seedTask("项目任务", owner, LocalDate.of(2026, 11, 20), "PENDING", adminId);
        MockHttpSession session = adminSession();

        mvc.perform(delete("/api/source-types/PROJECT").session(session).with(csrf()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("SOURCE_IN_USE"))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("1 条任务")));
    }

    @Test
    void nonAdminIsForbiddenAtTheEndpoint() throws Exception {
        seedOwner("owner1", "张三");
        MockHttpSession session = login("owner1");

        mvc.perform(get("/api/source-types").session(session))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/source-types").session(session).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"质量检查\",\"form\":\"NOTE\",\"color\":\"#067647\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void seededAdminSessionCanReadPaletteWithoutLoginHelper() throws Exception {
        MockHttpSession session = adminSession();
        MvcResult result = mvc.perform(get("/api/source-types/palette").session(session))
                .andExpect(status().isOk())
                .andReturn();
        assertThat(result.getResponse().getContentAsString()).contains("#1B4D8F", "#3F6212");
    }
}
