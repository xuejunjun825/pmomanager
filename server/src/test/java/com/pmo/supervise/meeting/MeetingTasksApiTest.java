package com.pmo.supervise.meeting;

import com.pmo.supervise.IntegrationTestSupport;
import com.pmo.supervise.common.BusinessException;
import com.pmo.supervise.common.ErrorCode;
import com.pmo.supervise.common.Role;
import com.pmo.supervise.meeting.dto.ConvertResult;
import com.pmo.supervise.meeting.dto.DecisionItemRequest;
import com.pmo.supervise.meeting.dto.MeetingDetail;
import com.pmo.supervise.meeting.dto.MeetingRequest;
import com.pmo.supervise.task.dto.TaskRow;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 会议任务区与转任务挂主题（AC-4 / AC-9，TECHNICAL §3.3）：
 * {@code GET /api/meetings/{id}/tasks} 只回本会议的任务（ADMIN，404/403 语义齐）；
 * 转任务一次批量写入 themeId + plannedStartDate，校验先于 INSERT，失败不留半条任务。
 */
@AutoConfigureMockMvc
class MeetingTasksApiTest extends IntegrationTestSupport {

    private static final String PASSWORD = "Admin@12345";

    @Autowired
    private MeetingService meetingService;

    @Autowired
    private DecisionConversionService conversionService;

    @Autowired
    private MockMvc mvc;

    private long owner;
    private long themeId;

    @BeforeEach
    void cleanThemes() {
        // tasks 已由基座清空，themes 可安全清空（fk_tasks_theme 是 RESTRICT）
        jdbc.execute("DELETE FROM themes");
        owner = seedOwner("meetingowner", "张三");
        themeId = seedTheme("质量提升", "#6941C6", true);
    }

    @AfterEach
    void dropSeededThemes() {
        jdbc.execute("DELETE FROM task_events");
        jdbc.execute("DELETE FROM urge_records");
        jdbc.execute("DELETE FROM tasks");
        jdbc.execute("DELETE FROM themes");
    }

    private long seedTheme(String name, String color, boolean enabled) {
        jdbc.update("INSERT INTO themes (name, color, sort_no, enabled) VALUES (?, ?, 0, ?)",
                name, color, enabled ? 1 : 0);
        return jdbc.queryForObject("SELECT id FROM themes WHERE name = ?", Long.class, name);
    }

    private MeetingDetail meetingWith(String title, LocalDate... dues) {
        List<DecisionItemRequest> items = new java.util.ArrayList<>();
        for (int i = 0; i < dues.length; i++) {
            items.add(new DecisionItemRequest(title + "事项" + (i + 1), owner, dues[i], null));
        }
        return meetingService.create(actor(adminId),
                new MeetingRequest(title, LocalDate.of(2026, 11, 1), "主持人", List.of(), null, items),
                clock.today());
    }

    private List<Long> itemIds(MeetingDetail detail) {
        return detail.items().stream().map(row -> row.id()).toList();
    }

    private long taskCount() {
        Long count = jdbc.queryForObject("SELECT COUNT(*) FROM tasks", Long.class);
        return count == null ? 0 : count;
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
    @DisplayName("转任务写主题与计划开始，任务区按截止日升序且只回本会议")
    void convertCarriesThemeAndTasksAreScopedToMeeting() {
        LocalDate planStart = LocalDate.of(2026, 11, 5);
        MeetingDetail current = meetingWith("周例会", LocalDate.of(2026, 11, 20), LocalDate.of(2026, 11, 18));
        MeetingDetail other = meetingWith("月例会", LocalDate.of(2026, 11, 15));

        ConvertResult result = conversionService.convert(current.id(), actor(adminId),
                itemIds(current), themeId, planStart);
        assertThat(result.created()).hasSize(2);
        assertThat(result.skipped()).isEmpty();
        conversionService.convert(other.id(), actor(adminId), itemIds(other), null, null);

        // 两列真的落库，不是只在响应里
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM tasks WHERE theme_id = ? AND planned_start_date = ?",
                Long.class, themeId, planStart)).isEqualTo(2);

        List<TaskRow> rows = meetingService.tasks(current.id(), actor(adminId), clock.today());
        assertThat(rows).hasSize(2);
        assertThat(rows).extracting(TaskRow::title).containsExactly("周例会事项2", "周例会事项1");
        assertThat(rows).extracting(TaskRow::dueDate)
                .containsExactly(LocalDate.of(2026, 11, 18), LocalDate.of(2026, 11, 20));
        assertThat(rows).extracting(TaskRow::plannedStartDate).containsOnly(planStart);
        assertThat(rows).extracting(TaskRow::theme).extracting("id").containsOnly(themeId);
        assertThat(rows.get(0).theme().name()).isEqualTo("质量提升");
        assertThat(rows.get(0).theme().color()).isEqualTo("#6941C6");
        assertThat(rows.get(0).theme().softColor()).isNotBlank();
        assertThat(rows.get(0).theme().borderColor()).isNotBlank();
        // 其他会议的任务不得混进来
        assertThat(rows).extracting(TaskRow::title).doesNotContain("月例会事项1");
        assertThat(meetingService.tasks(other.id(), actor(adminId), clock.today())).hasSize(1);
    }

    @Test
    @DisplayName("GET /api/meetings/{id}/tasks：ADMIN 200 且带主题三色，非 ADMIN 403，会议不存在 404")
    void tasksApiContract() throws Exception {
        MeetingDetail meeting = meetingWith("周例会", LocalDate.of(2026, 11, 20));
        conversionService.convert(meeting.id(), actor(adminId), itemIds(meeting), themeId, null);

        seedUser("meetingadmin", "会议管理员", Role.ADMIN, "ACTIVE");
        MockHttpSession admin = login("meetingadmin");
        mvc.perform(get("/api/meetings/" + meeting.id() + "/tasks").session(admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].theme.name").value("质量提升"))
                .andExpect(jsonPath("$[0].theme.color").value("#6941C6"))
                .andExpect(jsonPath("$[0].status").value("PENDING"))
                .andExpect(jsonPath("$[0].source.typeLabel").value("会议决议"));

        seedUser("meetingowner2", "普通负责人", Role.OWNER, "ACTIVE");
        MockHttpSession ownerSession = login("meetingowner2");
        mvc.perform(get("/api/meetings/" + meeting.id() + "/tasks").session(ownerSession))
                .andExpect(status().isForbidden());

        mockAdmin("meetingadmin3");
        MockHttpSession admin2 = login("meetingadmin3");
        mvc.perform(get("/api/meetings/999999/tasks").session(admin2))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("计划开始晚于任一决议截止日 → 422 plannedStartDate，一条任务都不留")
    void plannedStartAfterDecisionDueRejectsWholeBatch() {
        MeetingDetail meeting = meetingWith("周例会", LocalDate.of(2026, 11, 20), LocalDate.of(2026, 11, 18));

        assertThatThrownBy(() -> conversionService.convert(meeting.id(), actor(adminId),
                itemIds(meeting), themeId, LocalDate.of(2026, 11, 19)))
                .isInstanceOfSatisfying(BusinessException.class, ex -> {
                    assertThat(ex.code()).isEqualTo(ErrorCode.VALIDATION_FAILED);
                    assertThat(ex.fieldErrors()).extracting("field").containsExactly("plannedStartDate");
                });

        assertThat(taskCount()).isZero();
    }

    @Test
    @DisplayName("停用/不存在的主题 → 422 themeId，一条任务都不留")
    void disabledThemeRejectedBeforeInsert() {
        long disabled = seedTheme("已停用主题", "#0E9384", false);
        MeetingDetail meeting = meetingWith("周例会", LocalDate.of(2026, 11, 20));

        assertThatThrownBy(() -> conversionService.convert(meeting.id(), actor(adminId),
                itemIds(meeting), disabled, null))
                .isInstanceOfSatisfying(BusinessException.class, ex -> {
                    assertThat(ex.code()).isEqualTo(ErrorCode.VALIDATION_FAILED);
                    assertThat(ex.fieldErrors()).extracting("field").containsExactly("themeId");
                });
        assertThatThrownBy(() -> conversionService.convert(meeting.id(), actor(adminId),
                itemIds(meeting), 999999L, null))
                .isInstanceOfSatisfying(BusinessException.class, ex ->
                        assertThat(ex.fieldErrors()).extracting("field").containsExactly("themeId"));

        assertThat(taskCount()).isZero();
    }

    @Test
    @DisplayName("旧契约 JSON（只有 itemIds）仍可转任务：主题为空、不上计划开始")
    void legacyConvertPayloadStillWorks() throws Exception {
        MeetingDetail meeting = meetingWith("周例会", LocalDate.of(2026, 11, 20));
        seedUser("meetingadmin4", "会议管理员", Role.ADMIN, "ACTIVE");
        MockHttpSession admin = login("meetingadmin4");

        mvc.perform(post("/api/meetings/" + meeting.id() + "/convert").session(admin).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"itemIds\":[" + itemIds(meeting).get(0) + "]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.created.length()").value(1));

        assertThat(jdbc.queryForObject("SELECT theme_id FROM tasks", Long.class)).isNull();
        assertThat(jdbc.queryForObject("SELECT planned_start_date FROM tasks", LocalDate.class)).isNull();
        assertThat(meetingService.tasks(meeting.id(), actor(adminId), clock.today()))
                .singleElement().satisfies(row -> assertThat(row.theme()).isNull());
    }

    @Test
    @DisplayName("转任务带主题也走 HTTP 一趟：themeId/plannedStartDate 一次应用于整批")
    void httpConvertAppliesThemeToWholeBatch() throws Exception {
        MeetingDetail meeting = meetingWith("周例会", LocalDate.of(2026, 11, 20), LocalDate.of(2026, 11, 21));
        seedUser("meetingadmin5", "会议管理员", Role.ADMIN, "ACTIVE");
        MockHttpSession admin = login("meetingadmin5");

        LocalDate planStart = LocalDate.of(2026, 11, 6);
        mvc.perform(post("/api/meetings/" + meeting.id() + "/convert").session(admin).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"itemIds\":[%d,%d],\"themeId\":%d,\"plannedStartDate\":\"%s\"}"
                                .formatted(itemIds(meeting).get(0), itemIds(meeting).get(1), themeId, planStart)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.created.length()").value(2));

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM tasks WHERE theme_id = ?", Long.class, themeId))
                .isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM tasks WHERE planned_start_date = ?",
                Long.class, planStart)).isEqualTo(2);
    }

    private void mockAdmin(String username) {
        seedUser(username, "会议管理员", Role.ADMIN, "ACTIVE");
    }

    /**
     * 端到端闭环（7c）：真实 HTTP 走完主路径 —— 建会录 2 条决议 → 一次转任务（挂主题/计划开始）
     * → 负责人 start/submit → 管理员 accept → 会议 closed=true、unfinishedCount=0，
     * 且任务区接口的 status 与会议计数同源（AC-4 不得两套口径）。
     */
    @Test
    @DisplayName("端到端：HTTP 建会→转任务→推进→验收，会议走到 closed=true 终态")
    void endToEndMeetingLoopReachesClosed() throws Exception {
        seedUser("e2eadmin", "闭环管理员", Role.ADMIN, "ACTIVE");
        long e2eOwner = seedOwner("e2eowner", "李四");
        MockHttpSession admin = login("e2eadmin");
        MockHttpSession ownerSession = login("e2eowner");

        String createBody = ("{\"title\":\"闭环周会\",\"meetingDate\":\"2026-11-10\",\"host\":\"闭环管理员\","
                + "\"attendeeIds\":[],\"note\":null,\"items\":["
                + "{\"description\":\"补齐漏测用例\",\"ownerId\":%d,\"dueDate\":\"2026-11-25\",\"deliveryNote\":null},"
                + "{\"description\":\"清理历史告警\",\"ownerId\":%d,\"dueDate\":\"2026-11-26\",\"deliveryNote\":null}]}")
                .formatted(e2eOwner, e2eOwner);
        String createdBody = mvc.perform(post("/api/meetings").session(admin).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(createBody))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.items.length()").value(2))
                .andReturn().getResponse().getContentAsString();
        Number meetingId = com.jayway.jsonpath.JsonPath.read(createdBody, "$.id");
        List<Integer> itemIds = com.jayway.jsonpath.JsonPath.read(createdBody, "$.items[*].id");

        String convertBody = "{\"itemIds\":%s,\"themeId\":%d,\"plannedStartDate\":\"2026-11-11\"}"
                .formatted(itemIds, themeId);
        String convertResult = mvc.perform(post("/api/meetings/" + meetingId + "/convert")
                        .session(admin).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(convertBody))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.created.length()").value(2))
                .andReturn().getResponse().getContentAsString();
        List<Integer> taskIds = com.jayway.jsonpath.JsonPath.read(convertResult, "$.created[*].taskId");

        for (Integer taskId : taskIds) {
            mvc.perform(post("/api/tasks/" + taskId + "/start").session(ownerSession).with(csrf()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value("IN_PROGRESS"));
            mvc.perform(post("/api/tasks/" + taskId + "/submit").session(ownerSession).with(csrf())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"completionNote\":\"事项已办结，材料已归档并通知相关人\"}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value("PENDING_ACCEPT"));
            mvc.perform(post("/api/tasks/" + taskId + "/accept").session(admin).with(csrf()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value("DONE"));
        }

        // 终态：会议闭环，且任务区返回的两条任务状态与计数一致（同一份数据）
        mvc.perform(get("/api/meetings/" + meetingId).session(admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.taskCount").value(2))
                .andExpect(jsonPath("$.unfinishedCount").value(0))
                .andExpect(jsonPath("$.closed").value(true));
        mvc.perform(get("/api/meetings/" + meetingId + "/tasks").session(admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].status").value("DONE"))
                .andExpect(jsonPath("$[1].status").value("DONE"))
                .andExpect(jsonPath("$[0].theme.name").value("质量提升"))
                .andExpect(jsonPath("$[0].isOverdue").value(false));
    }
}
