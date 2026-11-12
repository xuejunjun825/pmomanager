package com.pmo.supervise.task;

import com.pmo.supervise.IntegrationTestSupport;
import com.pmo.supervise.common.BusinessException;
import com.pmo.supervise.common.ErrorCode;
import com.pmo.supervise.common.Priority;
import com.pmo.supervise.task.dto.CreateTaskRequest;
import com.pmo.supervise.task.dto.TaskDetailRow;
import com.pmo.supervise.task.dto.TaskFilter;
import com.pmo.supervise.task.dto.TaskRow;
import com.pmo.supervise.task.dto.UpdateTaskRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 任务域主题/计划开始集成测试（r7 / AC-9、AC-10）：
 * themeId 停用/不存在 → 422 字段级；themeId=0 清空；plannedStart > dueDate → 422；
 * 行上三色由服务端从 themes.color 派生；byMeeting 供会议进度区单一口径。
 *
 * <p>依赖 Flyway V7（themes 表 + tasks.theme_id/planned_start_date），
 * 由 T1 提供；表结构见 TECHNICAL §3.1。
 */
class TaskThemeTest extends IntegrationTestSupport {

    @Autowired
    private TaskService taskService;

    @Autowired
    private TaskQueryDao queryDao;

    private long owner;
    private final LocalDate today = LocalDate.of(2026, 11, 12);

    @BeforeEach
    void cleanThemes() {
        // tasks 已由基座清空，themes 可安全清空（fk_tasks_theme 是 RESTRICT）。
        jdbc.execute("DELETE FROM themes");
        owner = seedOwner("themeowner", "张三");
    }

    @AfterEach
    void dropSeededThemes() {
        // 基座不清 themes（T1 的表）：本类的数据不能漏给后面的主题用例。
        jdbc.execute("DELETE FROM task_events");
        jdbc.execute("DELETE FROM urge_records");
        jdbc.execute("DELETE FROM tasks");
        jdbc.execute("DELETE FROM themes");
    }

    @Test
    @DisplayName("建任务带启用主题与计划开始 → 行上三色派生、字段落库")
    void createWithThemeCarriesPalette() {
        long themeId = seedTheme("质量提升", "#6941C6", true);

        TaskDetailRow detail = taskService.create(actor(adminId), new CreateTaskRequest(
                "整改闭环", owner, LocalDate.of(2026, 11, 20), "PROJECT", null, null, "晨会安排",
                Priority.MEDIUM, null, themeId, LocalDate.of(2026, 11, 10)), today);

        assertThat(detail.theme()).isNotNull();
        assertThat(detail.theme().id()).isEqualTo(themeId);
        assertThat(detail.theme().name()).isEqualTo("质量提升");
        assertThat(detail.theme().color()).isEqualTo("#6941C6");
        assertThat(detail.theme().softColor()).isNotBlank();
        assertThat(detail.theme().borderColor()).isNotBlank();
        assertThat(detail.theme().enabled()).isTrue();
        assertThat(detail.plannedStartDate()).isEqualTo(LocalDate.of(2026, 11, 10));
        assertThat(detail.atRisk()).isFalse();
    }

    @Test
    @DisplayName("主题已停用 / 不存在 → 422 且 fieldErrors 指名 themeId")
    void disabledOrMissingThemeRejected() {
        long disabled = seedTheme("已停用主题", "#0E9384", false);

        assertThatThrownBy(() -> taskService.create(actor(adminId), request("停用主题", disabled, null), today))
                .isInstanceOfSatisfying(BusinessException.class, ex -> {
                    assertThat(ex.code()).isEqualTo(ErrorCode.VALIDATION_FAILED);
                    assertThat(ex.fieldErrors()).extracting(f -> f.field()).contains("themeId");
                });

        assertThatThrownBy(() -> taskService.create(actor(adminId), request("不存在主题", 999999L, null), today))
                .isInstanceOfSatisfying(BusinessException.class, ex ->
                        assertThat(ex.fieldErrors()).extracting(f -> f.field()).contains("themeId"));
    }

    @Test
    @DisplayName("计划开始晚于截止日期 → 422 plannedStartDate；同日合法")
    void plannedStartAfterDueRejected() {
        LocalDate due = LocalDate.of(2026, 11, 20);

        assertThatThrownBy(() -> taskService.create(actor(adminId),
                request("倒挂工期", null, due.plusDays(1)), today))
                .isInstanceOfSatisfying(BusinessException.class, ex -> {
                    assertThat(ex.code()).isEqualTo(ErrorCode.VALIDATION_FAILED);
                    assertThat(ex.fieldErrors()).extracting(f -> f.field()).contains("plannedStartDate");
                });

        TaskDetailRow sameDay = taskService.create(actor(adminId), request("同日", null, due), today);
        assertThat(sameDay.plannedStartDate()).isEqualTo(due);
    }

    @Test
    @DisplayName("编辑：themeId=0 清空主题，clearPlannedStart 清空计划开始")
    void updateCanClearThemeAndPlannedStart() {
        long themeId = seedTheme("安全合规", "#D92D20", true);
        long taskId = taskService.create(actor(adminId), new CreateTaskRequest(
                "清理验证", owner, LocalDate.of(2026, 11, 20), "PROJECT", null, null, "晨会安排",
                Priority.MEDIUM, null, themeId, LocalDate.of(2026, 11, 10)), today).id();

        TaskDetailRow cleared = taskService.update(taskId, actor(adminId), new UpdateTaskRequest(
                null, null, null, null, null, null, null, null, null, 0L, null, true), today);

        assertThat(cleared.theme()).isNull();
        assertThat(cleared.plannedStartDate()).isNull();
        assertThat(jdbc.queryForObject("SELECT theme_id FROM tasks WHERE id = ?", Long.class, taskId)).isNull();
    }

    @Test
    @DisplayName("编辑：只改计划开始，null 主题保持不变（不清空）")
    void nullThemeKeepsExisting() {
        long themeId = seedTheme("成本优化", "#175CD3", true);
        long taskId = taskService.create(actor(adminId), new CreateTaskRequest(
                "保持主题", owner, LocalDate.of(2026, 11, 20), "PROJECT", null, null, "晨会安排",
                Priority.MEDIUM, null, themeId, null), today).id();

        TaskDetailRow detail = taskService.update(taskId, actor(adminId), new UpdateTaskRequest(
                null, null, null, null, null, null, null, null, null, null,
                LocalDate.of(2026, 11, 8), false), today);

        assertThat(detail.theme()).isNotNull();
        assertThat(detail.theme().id()).isEqualTo(themeId);
        assertThat(detail.plannedStartDate()).isEqualTo(LocalDate.of(2026, 11, 8));
    }

    @Test
    @DisplayName("主题筛选 none 只出未归主题；指定 id 只出该主题")
    void themeFilterSplitsAssignedAndUnassigned() {
        long themeId = seedTheme("数字化", "#7A5AF8", true);
        taskService.create(actor(adminId), request("有主题", themeId, null), today);
        taskService.create(actor(adminId), request("无主题", null, null), today);

        TaskFilter unassigned = new TaskFilter();
        unassigned.setTheme(ThemeFilterSql.UNASSIGNED);
        assertThat(queryDao.page(unassigned, today, 1, 20).items())
                .extracting(TaskRow::title).containsExactly("无主题");

        TaskFilter assigned = new TaskFilter();
        assigned.setTheme(String.valueOf(themeId));
        assertThat(queryDao.page(assigned, today, 1, 20).items())
                .extracting(TaskRow::title).containsExactly("有主题");
    }

    @Test
    @DisplayName("byMeeting 只返回该会议的任务，按截止日升序")
    void byMeetingReturnsOnlyThatMeeting() {
        long meeting = seedMeeting("产品周会", adminId);
        long item1 = seedDecisionItem(meeting, "输出纪要", owner);
        long item2 = seedDecisionItem(meeting, "排期评审", owner);
        seedMeetingTask("晚的", owner, today.plusDays(5), "PENDING", adminId, meeting, item1);
        seedMeetingTask("早的", owner, today.plusDays(1), "PENDING", adminId, meeting, item2);
        long other = seedMeeting("别的会", adminId);
        seedMeetingTask("不在本会", owner, today.plusDays(2), "PENDING", adminId, other,
                seedDecisionItem(other, "别处", owner));

        assertThat(queryDao.byMeeting(meeting, today)).extracting(TaskRow::title)
                .containsExactly("早的", "晚的");
    }

    @Test
    @DisplayName("KPI 第 7 项 risk = 逾期 ∪ 待验收满 3 天，与行 atRisk 同数")
    void countersRiskMatchesRowFlag() {
        long pendingAccept = seedTask("待验收久了", owner, today.plusDays(5), "PENDING_ACCEPT", adminId);
        jdbc.update("UPDATE tasks SET submitted_at = ? WHERE id = ?",
                today.minusDays(TaskRiskSpec.PENDING_ACCEPT_DAYS).atTime(9, 0), pendingAccept);
        seedTask("逾期", owner, today.minusDays(2), "IN_PROGRESS", adminId);
        seedTask("正常", owner, today.plusDays(3), "PENDING", adminId);

        assertThat(queryDao.counters(new TaskFilter(), today).risk()).isEqualTo(2);
        // 同一口径的行级标记：列表里打 atRisk 的正好是这 2 条（SQL 与 counters 逐条相等）。
        assertThat(queryDao.page(new TaskFilter(), today, 1, 20).items())
                .filteredOn(TaskRow::atRisk)
                .extracting(TaskRow::title)
                .containsExactlyInAnyOrder("待验收久了", "逾期");
    }

    private long seedTheme(String name, String color, boolean enabled) {
        jdbc.update("INSERT INTO themes (name, color, sort_no, enabled) VALUES (?, ?, 0, ?)",
                name, color, enabled ? 1 : 0);
        return jdbc.queryForObject("SELECT id FROM themes WHERE name = ?", Long.class, name);
    }

    private CreateTaskRequest request(String title, Long themeId, LocalDate plannedStart) {
        return new CreateTaskRequest(title, owner, LocalDate.of(2026, 11, 20), "PROJECT", null, null,
                "晨会安排", Priority.MEDIUM, null, themeId, plannedStart);
    }
}
