package com.pmo.supervise.theme;

import com.pmo.supervise.IntegrationTestSupport;
import com.pmo.supervise.common.Role;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * /api/themes 六个端点与失败语义（TECHNICAL §3.1 / AC-8）：
 * 全员可读、写仅 ADMIN、名称/颜色/上限/被引用 409、停用后不可选、meta 一次带全主题。
 */
@AutoConfigureMockMvc
class ThemeApiTest extends IntegrationTestSupport {

    private static final String PASSWORD = "Admin@12345";

    @Autowired
    private MockMvc mvc;

    @BeforeEach
    void cleanThemes() {
        jdbc.update("UPDATE tasks SET theme_id = NULL WHERE theme_id IS NOT NULL");
        jdbc.update("DELETE FROM themes");
    }

    @AfterEach
    void leaveNoThemeRows() {
        jdbc.update("UPDATE tasks SET theme_id = NULL WHERE theme_id IS NOT NULL");
        jdbc.update("DELETE FROM themes");
    }

    private MockHttpSession login(String username) throws Exception {
        MvcResult result = mvc.perform(post("/api/auth/login").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + username + "\",\"password\":\"" + PASSWORD + "\"}"))
                .andExpect(status().isOk())
                .andReturn();
        return (MockHttpSession) result.getRequest().getSession(false);
    }

    private MockHttpSession adminSession() throws Exception {
        seedUser("thmadmin", "主题管理员", Role.ADMIN, "ACTIVE");
        return login("thmadmin");
    }

    private long createTheme(MockHttpSession session, String body) throws Exception {
        MvcResult result = mvc.perform(post("/api/themes").session(session).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andReturn();
        return ((Number) com.jayway.jsonpath.JsonPath.read(
                result.getResponse().getContentAsString(), "$.id")).longValue();
    }

    @Test
    void crudRoundTripWithDerivedColorsAndPalette() throws Exception {
        MockHttpSession session = adminSession();

        mvc.perform(get("/api/themes").session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(0)));

        mvc.perform(get("/api/themes/palette").session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(8)))
                .andExpect(jsonPath("$[0].color").value("#1B4D8F"))
                .andExpect(jsonPath("$[0].soft").value("#EAF1FA"))
                .andExpect(jsonPath("$[0].border").value("#C7D9EF"))
                .andExpect(jsonPath("$[0].name").value("墨蓝"));

        long id = createTheme(session, "{\"name\":\"Q4 交付冲刺\",\"sort\":3}");
        mvc.perform(get("/api/themes").session(session))
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].name").value("Q4 交付冲刺"))
                .andExpect(jsonPath("$[0].color").value("#1B4D8F"))
                .andExpect(jsonPath("$[0].softColor").value("#EAF1FA"))
                .andExpect(jsonPath("$[0].borderColor").value("#C7D9EF"))
                .andExpect(jsonPath("$[0].sort").value(3))
                .andExpect(jsonPath("$[0].enabled").value(true))
                .andExpect(jsonPath("$[0].usageCount").value(0));

        mvc.perform(put("/api/themes/" + id).session(session).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Q4 冲刺\",\"color\":\"#067647\",\"sort\":5}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Q4 冲刺"))
                .andExpect(jsonPath("$.color").value("#067647"))
                .andExpect(jsonPath("$.softColor").value("#ECFDF3"))
                .andExpect(jsonPath("$.borderColor").value("#ABEFC6"))
                .andExpect(jsonPath("$.sort").value(5));

        mvc.perform(patch("/api/themes/" + id + "/status").session(session).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"enabled\":false}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enabled").value(false));

        mvc.perform(delete("/api/themes/" + id).session(session).with(csrf()))
                .andExpect(status().isNoContent());
        mvc.perform(get("/api/themes").session(session))
                .andExpect(jsonPath("$", hasSize(0)));
    }

    @Test
    void validationAndConflictCodesAreReadable() throws Exception {
        MockHttpSession session = adminSession();
        createTheme(session, "{\"name\":\"唯一主题\"}");

        mvc.perform(post("/api/themes").session(session).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"唯一主题\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("THEME_NAME_DUPLICATE"))
                .andExpect(jsonPath("$.fieldErrors[0].field").value("name"));

        mvc.perform(post("/api/themes").session(session).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"主\"}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.fieldErrors[0].field").value("name"));

        mvc.perform(post("/api/themes").session(session).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"非法颜色主题\",\"color\":\"#000000\"}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.fieldErrors[0].field").value("color"));

        mvc.perform(post("/api/themes").session(session).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"  \"}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));

        mvc.perform(put("/api/themes/999999").session(session).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"不存在的主题\"}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"))
                .andExpect(jsonPath("$.message").value("主题不存在"));

        mvc.perform(delete("/api/themes/999999").session(session).with(csrf()))
                .andExpect(status().isNotFound());
    }

    @Test
    void nonAdminCanReadButNotWrite() throws Exception {
        MockHttpSession admin = adminSession();
        createTheme(admin, "{\"name\":\"只读可见主题\"}");

        seedUser("thmowner", "任务负责人", Role.OWNER, "ACTIVE");
        MockHttpSession owner = login("thmowner");

        mvc.perform(get("/api/themes").session(owner))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].name").value("只读可见主题"));
        mvc.perform(get("/api/themes/palette").session(owner))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(8)));

        mvc.perform(post("/api/themes").session(owner).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"越权主题\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
        mvc.perform(put("/api/themes/1").session(owner).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"越权改名\"}"))
                .andExpect(status().isForbidden());
        mvc.perform(patch("/api/themes/1/status").session(owner).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"enabled\":false}"))
                .andExpect(status().isForbidden());
        mvc.perform(delete("/api/themes/1").session(owner).with(csrf()))
                .andExpect(status().isForbidden());
    }

    @Test
    void referencedThemeReportsUsageAndDeleteConflict() throws Exception {
        MockHttpSession session = adminSession();
        long themeId = createTheme(session, "{\"name\":\"被引用主题\",\"color\":\"#6941C6\"}");

        long ownerId = seedUser("thmowner", "任务负责人", Role.OWNER, "ACTIVE");
        long taskId = seedTask("挂主题的任务", ownerId, LocalDate.of(2026, 11, 20), "PENDING", ownerId);
        jdbc.update("UPDATE tasks SET theme_id = ? WHERE id = ?", themeId, taskId);

        mvc.perform(get("/api/themes").session(session))
                .andExpect(jsonPath("$[0].usageCount").value(1));

        mvc.perform(delete("/api/themes/" + themeId).session(session).with(csrf()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("THEME_IN_USE"))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("1 条任务")))
                .andExpect(jsonPath("$.details.taskCount").value(1));

        mvc.perform(patch("/api/themes/" + themeId + "/status").session(session).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"enabled\":false}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enabled").value(false))
                .andExpect(jsonPath("$.usageCount").value(1));

        // 停用后仍被引用：物理删除继续 409（只允许停用）。
        mvc.perform(delete("/api/themes/" + themeId).session(session).with(csrf()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("THEME_IN_USE"));
    }

    @Test
    void metaExposesThemesIncludingDisabledWithThreeColors() throws Exception {
        MockHttpSession session = adminSession();
        createTheme(session, "{\"name\":\"启用主题\",\"sort\":1}");
        createTheme(session, "{\"name\":\"停用主题\",\"color\":\"#B54708\",\"sort\":2,"
                + "\"enabled\":false}");

        mvc.perform(get("/api/meta/options").session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.themes", hasSize(2)))
                .andExpect(jsonPath("$.themes[0].name").value("启用主题"))
                .andExpect(jsonPath("$.themes[0].color").value("#1B4D8F"))
                .andExpect(jsonPath("$.themes[0].softColor").value("#EAF1FA"))
                .andExpect(jsonPath("$.themes[0].borderColor").value("#C7D9EF"))
                .andExpect(jsonPath("$.themes[0].enabled").value(true))
                .andExpect(jsonPath("$.themes[1].name").value("停用主题"))
                .andExpect(jsonPath("$.themes[1].color").value("#B54708"))
                .andExpect(jsonPath("$.themes[1].enabled").value(false))
                // 选项不带 usageCount，meta 不做 count 子查询。
                .andExpect(jsonPath("$.themes[0].usageCount").doesNotExist());
    }

    @Test
    void defaultColorFollowsCreationOrderAndTwentyCodePointNameIsAccepted() throws Exception {
        MockHttpSession session = adminSession();
        createTheme(session, "{\"name\":\"第一个主题\"}");
        createTheme(session, "{\"name\":\"第二个主题\"}");
        createTheme(session, "{\"name\":\"一二三四五六七八九十一二三四五六七八九十\"}");

        mvc.perform(get("/api/themes").session(session))
                .andExpect(jsonPath("$", hasSize(3)))
                .andExpect(jsonPath("$[0].color").value("#1B4D8F"))
                .andExpect(jsonPath("$[1].color").value("#3538CD"))
                .andExpect(jsonPath("$[2].color").value("#0B7A6E"));

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM themes", Integer.class)).isEqualTo(3);
    }
}
