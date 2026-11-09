package com.pmo.supervise.settings;

import com.pmo.supervise.IntegrationTestSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * /api/settings 的 HTTP 契约与 AC-20 的“名单为空”提示（TECHNICAL §5.5）。
 * 设置行不会随 IntegrationTestSupport 清库，故每个用例前把五个键恢复成种子值。
 */
@AutoConfigureMockMvc
class SettingsHttpIT extends IntegrationTestSupport {

    private static final String PASSWORD = "Admin@12345";

    @Autowired
    private MockMvc mvc;

    @BeforeEach
    void resetSettings() {
        jdbc.update("UPDATE settings SET v = 'true' WHERE k = 'report.daily.enabled'");
        jdbc.update("UPDATE settings SET v = '18:00' WHERE k = 'report.daily.time'");
        jdbc.update("UPDATE settings SET v = 'true' WHERE k = 'report.weekly.enabled'");
        jdbc.update("UPDATE settings SET v = '09:00' WHERE k = 'report.weekly.time'");
        jdbc.update("UPDATE settings SET v = '[]' WHERE k = 'report.recipients'");
    }

    private MockHttpSession login(String username) throws Exception {
        MvcResult result = mvc.perform(post("/api/auth/login").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + username + "\",\"password\":\"" + PASSWORD + "\"}"))
                .andExpect(status().isOk())
                .andReturn();
        return (MockHttpSession) result.getRequest().getSession(false);
    }

    @Test
    void adminReadsDefaultsWithEmptyRecipientsFlag() throws Exception {
        long admin = seedUser("settingsadmin", "设置管理员", com.pmo.supervise.common.Role.ADMIN, "ACTIVE");
        MockHttpSession session = login("settingsadmin");

        mvc.perform(get("/api/settings").session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.report.dailyEnabled").value(true))
                .andExpect(jsonPath("$.report.dailyTime").value("18:00"))
                .andExpect(jsonPath("$.report.weeklyEnabled").value(true))
                .andExpect(jsonPath("$.report.weeklyTime").value("09:00"))
                .andExpect(jsonPath("$.report.recipientsEmpty").value(true))
                .andExpect(jsonPath("$.report.recipients.length()").value(0))
                .andExpect(jsonPath("$.dingtalk.configured").value(false))
                .andExpect(jsonPath("$.dingtalk.missingKeys").isArray())
                .andExpect(jsonPath("$.updatedAt").isNotEmpty());

        assertThat(admin).isPositive();
    }

    @Test
    void ownerCannotReadOrWriteSettings() throws Exception {
        long owner = seedOwner("settingsowner", "设置负责人");
        MockHttpSession session = login("settingsowner");

        mvc.perform(get("/api/settings").session(session))
                .andExpect(status().isForbidden());

        mvc.perform(put("/api/settings/report").session(session).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"dailyEnabled":true,"dailyTime":"18:00","weeklyEnabled":false,
                                 "weeklyTime":"09:00","recipientIds":[%d]}
                                """.formatted(owner)))
                .andExpect(status().isForbidden());
    }

    @Test
    void adminSavesRecipientsAndTimesThenReadsThemBack() throws Exception {
        seedUser("settingsadmin2", "设置管理员二", com.pmo.supervise.common.Role.ADMIN, "ACTIVE");
        long exec = seedExec("settingsexec", "指定领导");
        MockHttpSession session = login("settingsadmin2");

        mvc.perform(put("/api/settings/report").session(session).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"dailyEnabled":true,"dailyTime":"17:30","weeklyEnabled":false,
                                 "weeklyTime":"08:15","recipientIds":[%d]}
                                """.formatted(exec)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.dailyTime").value("17:30"))
                .andExpect(jsonPath("$.weeklyEnabled").value(false))
                .andExpect(jsonPath("$.recipientsEmpty").value(false))
                .andExpect(jsonPath("$.recipients[0].id").value(exec));

        mvc.perform(get("/api/settings").session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.report.dailyTime").value("17:30"))
                .andExpect(jsonPath("$.report.weeklyTime").value("08:15"))
                .andExpect(jsonPath("$.report.weeklyEnabled").value(false))
                .andExpect(jsonPath("$.report.recipientsEmpty").value(false))
                .andExpect(jsonPath("$.report.recipients[0].name").value("指定领导"))
                .andExpect(jsonPath("$.updatedAt").exists());

        Integer audited = jdbc.queryForObject(
                "SELECT COUNT(*) FROM audit_logs WHERE action = 'SETTINGS_UPDATED'", Integer.class);
        assertThat(audited).isEqualTo(1);
    }

    @Test
    void blankTimeOnDisabledChannelKeepsStoredTime() throws Exception {
        seedUser("settingsadmin4", "设置管理员四", com.pmo.supervise.common.Role.ADMIN, "ACTIVE");
        MockHttpSession session = login("settingsadmin4");

        mvc.perform(put("/api/settings/report").session(session).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"dailyEnabled":false,"dailyTime":"","weeklyEnabled":false,
                                 "weeklyTime":"","recipientIds":[]}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.dailyEnabled").value(false))
                .andExpect(jsonPath("$.dailyTime").value("18:00"))
                .andExpect(jsonPath("$.weeklyTime").value("09:00"));
    }

    @Test
    void invalidTimeAndUnknownRecipientAreRejectedWith422() throws Exception {
        seedUser("settingsadmin3", "设置管理员三", com.pmo.supervise.common.Role.ADMIN, "ACTIVE");
        MockHttpSession session = login("settingsadmin3");

        mvc.perform(put("/api/settings/report").session(session).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"dailyEnabled":true,"dailyTime":"25:00","weeklyEnabled":true,
                                 "weeklyTime":"09:00","recipientIds":[]}
                                """))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));

        mvc.perform(put("/api/settings/report").session(session).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"dailyEnabled":true,"dailyTime":"18:00","weeklyEnabled":true,
                                 "weeklyTime":"09:00","recipientIds":[999999]}
                                """))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));

        String recipients = jdbc.queryForObject(
                "SELECT v FROM settings WHERE k = 'report.recipients'", String.class);
        assertThat(recipients).isEqualTo("[]");
    }
}
