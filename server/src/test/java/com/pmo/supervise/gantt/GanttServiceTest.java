package com.pmo.supervise.gantt;

import com.pmo.supervise.IntegrationTestSupport;
import com.pmo.supervise.common.BusinessException;
import com.pmo.supervise.common.ErrorCode;
import com.pmo.supervise.gantt.dto.GanttQuery;
import com.pmo.supervise.gantt.dto.GanttView;
import com.pmo.supervise.task.TaskQueryDao;
import com.pmo.supervise.task.dto.TaskCounters;
import com.pmo.supervise.task.dto.TaskFilter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 甘特聚合（TECHNICAL §3.4 / §6.2 / AC-7、AC-10、AC-12）：
 * 窗口裁剪、逾期 pin、右界外风险夹标、300 截断、主题/项目分组顺序、未归主题置底、
 * summary 与看板 counters 同源、OWNER 403、主题筛选复用 ThemeFilterSql。
 */
class GanttServiceTest extends IntegrationTestSupport {

    private static final LocalDate TODAY = LocalDate.of(2026, 11, 12);
    private static final LocalDate FROM = LocalDate.of(2026, 11, 1);
    private static final LocalDate TO = LocalDate.of(2026, 11, 30);

    @Autowired
    private GanttService gantt;

    @Autowired
    private TaskQueryDao taskQueryDao;

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

    @Test
    void windowKeepsOverdueAndRiskTasksOutsideTheWindowAndPinsThem() {
        long owner = seedOwner("gantt-owner", "甘特负责人");
        long themeA = seedTheme("主题A", "#2F6BFF", 0);
        long themeB = seedTheme("主题B", "#12B76A", 10);

        long inWindow = task("窗口内计划任务", owner, LocalDate.of(2026, 11, 20), "IN_PROGRESS",
                themeA, LocalDate.of(2026, 11, 10), LocalDate.of(2026, 11, 1), null);
        long overdue = task("窗口外逾期", owner, LocalDate.of(2026, 9, 1), "PENDING",
                themeA, null, LocalDate.of(2026, 8, 1), null);
        long done = task("窗口内已完成", owner, LocalDate.of(2026, 11, 18), "DONE",
                themeA, null, LocalDate.of(2026, 11, 1), null);
        long riskBeyondRight = task("右界外风险", owner, LocalDate.of(2027, 1, 15), "PENDING_ACCEPT",
                themeB, null, LocalDate.of(2026, 11, 5), LocalDateTime.of(2026, 11, 8, 9, 0));
        long unassigned = task("未归主题", owner, LocalDate.of(2026, 11, 25), "PENDING",
                null, null, LocalDate.of(2026, 11, 2), null);
        // 起点落在右界之后才算「窗口外」：横跨窗口的条按窗口语义本来就要返回（右端夹标）。
        long farFuture = task("窗口外且无风险", owner, LocalDate.of(2027, 7, 1), "PENDING",
                themeB, LocalDate.of(2027, 6, 1), LocalDate.of(2026, 11, 1), null);

        GanttView view = gantt.load(actor(adminId), TODAY, window(TO));

        assertThat(allIds(view)).containsExactlyInAnyOrder(inWindow, overdue, done, riskBeyondRight, unassigned);
        assertThat(allIds(view)).doesNotContain(farFuture);
        assertThat(view.truncated()).isFalse();
        assertThat(view.windowTotal()).isEqualTo(5);

        // 逾期条贴左边界：起点按创建日推算（estimatedStart），左帽虚线由 pinnedStart 决定。
        GanttView.Task overdueView = find(view, overdue);
        assertThat(overdueView.pinnedStart()).isTrue();
        assertThat(overdueView.estimatedStart()).isTrue();
        assertThat(overdueView.startDate()).isEqualTo(LocalDate.of(2026, 8, 1));
        assertThat(overdueView.isOverdue()).isTrue();
        assertThat(overdueView.overdueDays()).isEqualTo(72);
        assertThat(overdueView.isAtRisk()).isTrue();

        // 右界外的风险条：pinnedEnd 夹标，起点不 pin。
        GanttView.Task riskView = find(view, riskBeyondRight);
        assertThat(riskView.pinnedEnd()).isTrue();
        assertThat(riskView.pinnedStart()).isFalse();
        assertThat(riskView.isAtRisk()).isTrue();
        assertThat(riskView.isOverdue()).isFalse();

        GanttView.Task planned = find(view, inWindow);
        assertThat(planned.startDate()).isEqualTo(LocalDate.of(2026, 11, 10));
        assertThat(planned.estimatedStart()).isFalse();
        assertThat(planned.pinnedStart()).isFalse();
        assertThat(planned.pinnedEnd()).isFalse();

        // 分组顺序：主题按 sort_no，未归主题恒置底；组内计数一次累加。
        assertThat(view.groups()).extracting(GanttView.ThemeGroup::themeName)
                .containsExactly("主题A", "主题B", "未归主题");
        GanttView.ThemeGroup groupA = view.groups().get(0);
        assertThat(groupA.themeId()).isEqualTo(themeA);
        assertThat(groupA.taskCount()).isEqualTo(3);
        assertThat(groupA.overdueCount()).isEqualTo(1);
        assertThat(groupA.riskCount()).isEqualTo(1);
        assertThat(groupA.projects()).hasSize(1);
        assertThat(groupA.projects().get(0).unspecified()).isTrue();
        assertThat(groupA.projects().get(0).projectId()).isNull();
        // 组内排序：逾期最前，其次进行中，最后已完成。
        assertThat(groupA.projects().get(0).tasks()).extracting(GanttView.Task::id)
                .containsExactly(overdue, inWindow, done);

        GanttView.ThemeGroup unassignedGroup = view.groups().get(2);
        assertThat(unassignedGroup.unassigned()).isTrue();
        assertThat(unassignedGroup.themeId()).isNull();
        assertThat(unassignedGroup.taskCount()).isEqualTo(1);

        // 摘要：逾期/风险与看板同一个 counters，其余是窗口口径。
        assertThat(view.summary().overdue()).isEqualTo(1);
        assertThat(view.summary().risk()).isEqualTo(2);
        assertThat(view.summary().inProgress()).isEqualTo(1);
        assertThat(view.summary().pendingAccept()).isEqualTo(1);
        assertThat(view.summary().done()).isEqualTo(1);
        assertThat(view.summary().unassigned()).isEqualTo(1);
    }

    @Test
    void summaryOverdueAndRiskMatchTheBoardCounters() {
        long owner = seedOwner("gantt-consistency", "同源负责人");
        long theme = seedTheme("同源主题", "#2F6BFF", 0);
        task("逾期一", owner, LocalDate.of(2026, 9, 1), "PENDING", theme, null, LocalDate.of(2026, 8, 1), null);
        task("逾期二", owner, LocalDate.of(2026, 10, 1), "IN_PROGRESS", theme, null, LocalDate.of(2026, 9, 1), null);
        task("待验收满三天", owner, LocalDate.of(2026, 11, 20), "PENDING_ACCEPT", theme, null,
                LocalDate.of(2026, 11, 1), LocalDateTime.of(2026, 11, 8, 9, 0));
        task("正常", owner, LocalDate.of(2026, 11, 25), "PENDING", theme, null, LocalDate.of(2026, 11, 2), null);

        TaskFilter filter = new TaskFilter();
        filter.setTheme(String.valueOf(theme));
        TaskCounters counters = taskQueryDao.counters(filter, TODAY);

        GanttView view = gantt.load(actor(adminId), TODAY,
                new GanttQuery(FROM, TO, null, true, false, String.valueOf(theme)));

        assertThat(view.summary().overdue()).isEqualTo(counters.overdue()).isEqualTo(2);
        assertThat(view.summary().risk()).isEqualTo(counters.risk()).isEqualTo(3);
        // 主题筛选只走 ThemeFilterSql：别的主题不会混进来。
        assertThat(view.groups()).hasSize(1);
        assertThat(view.groups().get(0).themeId()).isEqualTo(theme);
    }

    @Test
    void themeFilterNoneKeepsOnlyUnassignedTasks() {
        long owner = seedOwner("gantt-none", "未归负责人");
        long theme = seedTheme("有主题", "#2F6BFF", 0);
        long assigned = task("有主题任务", owner, LocalDate.of(2026, 11, 20), "PENDING",
                theme, LocalDate.of(2026, 11, 5), LocalDate.of(2026, 11, 1), null);
        long unassigned = task("未归任务", owner, LocalDate.of(2026, 11, 21), "PENDING",
                null, LocalDate.of(2026, 11, 6), LocalDate.of(2026, 11, 1), null);

        GanttView none = gantt.load(actor(adminId), TODAY,
                new GanttQuery(FROM, TO, null, true, false, "none"));
        assertThat(allIds(none)).containsExactly(unassigned);
        assertThat(allIds(none)).doesNotContain(assigned);
        assertThat(none.summary().unassigned()).isEqualTo(1);

        assertThatThrownBy(() -> gantt.load(actor(adminId), TODAY,
                new GanttQuery(FROM, TO, null, true, false, "abc")))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).code())
                .isEqualTo(ErrorCode.THEME_INVALID);
    }

    @Test
    void onlyRiskAndOwnerFiltersNarrowTheSameWindow() {
        long owner = seedOwner("gantt-risk", "风险负责人");
        long other = seedOwner("gantt-other", "其他负责人");
        long risky = task("风险任务", owner, LocalDate.of(2026, 9, 1), "PENDING", null,
                null, LocalDate.of(2026, 8, 1), null);
        long normal = task("正常任务", owner, LocalDate.of(2026, 11, 20), "PENDING", null,
                LocalDate.of(2026, 11, 5), LocalDate.of(2026, 11, 1), null);
        long doneTask = task("已完成任务", owner, LocalDate.of(2026, 11, 18), "DONE", null,
                LocalDate.of(2026, 11, 5), LocalDate.of(2026, 11, 1), null);
        long others = task("别人的任务", other, LocalDate.of(2026, 11, 20), "PENDING", null,
                LocalDate.of(2026, 11, 5), LocalDate.of(2026, 11, 1), null);

        GanttView onlyRisk = gantt.load(actor(adminId), TODAY,
                new GanttQuery(FROM, TO, null, true, true, null));
        assertThat(allIds(onlyRisk)).containsExactly(risky);
        assertThat(onlyRisk.summary().risk()).isEqualTo(1);

        GanttView byOwner = gantt.load(actor(adminId), TODAY,
                new GanttQuery(FROM, TO, owner, true, false, null));
        assertThat(allIds(byOwner)).containsExactlyInAnyOrder(risky, normal, doneTask);

        GanttView withoutDone = gantt.load(actor(adminId), TODAY,
                new GanttQuery(FROM, TO, null, false, false, null));
        assertThat(allIds(withoutDone)).containsExactlyInAnyOrder(risky, normal, others);
        assertThat(allIds(withoutDone)).doesNotContain(doneTask);
    }

    @Test
    void truncatesAtThreeHundredRowsAndReportsTheWindowTotal() {
        long owner = seedOwner("gantt-bulk", "批量负责人");
        for (int i = 0; i < 305; i++) {
            task("批量任务 " + i, owner, LocalDate.of(2026, 11, 20), "PENDING", null,
                    LocalDate.of(2026, 11, 5), LocalDate.of(2026, 11, 1), null);
        }

        GanttView view = gantt.load(actor(adminId), TODAY, window(TO));

        assertThat(view.truncated()).isTrue();
        assertThat(view.windowTotal()).isEqualTo(305);
        assertThat(allIds(view)).hasSize(GanttDao.MAX_ROWS);
        assertThat(view.groups()).hasSize(1);
        assertThat(view.groups().get(0).taskCount()).isEqualTo(GanttDao.MAX_ROWS);
    }

    @Test
    void ownerRoleIsForbidden() {
        long owner = seedOwner("gantt-forbidden", "无权负责人");
        assertThatThrownBy(() -> gantt.load(actor(owner), TODAY, window(TO)))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).code())
                .isEqualTo(ErrorCode.FORBIDDEN);
    }

    private static GanttQuery window(LocalDate to) {
        return new GanttQuery(FROM, to, null, true, false, null);
    }

    private long seedTheme(String name, String color, int sort) {
        jdbc.update("INSERT INTO themes (name, color, sort_no, enabled) VALUES (?, ?, ?, 1)",
                name, color, sort);
        return jdbc.queryForObject("SELECT MAX(id) FROM themes", Long.class);
    }

    /** created_at 显式给值：甘特在 planned_start_date 为空时按创建日推算起点。 */
    private long task(String title, long ownerId, LocalDate due, String status, Long themeId,
                      LocalDate plannedStart, LocalDate created, LocalDateTime submittedAt) {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement ps = connection.prepareStatement("""
                    INSERT INTO tasks (title, owner_id, due_date, source_type, source_note, status,
                                       dispatcher_id, priority, theme_id, planned_start_date, created_at, submitted_at)
                    VALUES (?, ?, ?, 'PROJECT', '甘特测试', ?, ?, 'MEDIUM', ?, ?, ?, ?)
                    """, Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, title);
            ps.setLong(2, ownerId);
            ps.setObject(3, due);
            ps.setString(4, status);
            ps.setLong(5, ownerId);
            ps.setObject(6, themeId);
            ps.setObject(7, plannedStart);
            ps.setObject(8, created.atStartOfDay());
            ps.setObject(9, submittedAt);
            return ps;
        }, keys);
        return Objects.requireNonNull(keys.getKey(), "tasks 未返回自增主键").longValue();
    }

    private static List<Long> allIds(GanttView view) {
        return view.groups().stream()
                .flatMap(group -> group.projects().stream())
                .flatMap(project -> project.tasks().stream())
                .map(GanttView.Task::id)
                .toList();
    }

    private static GanttView.Task find(GanttView view, long id) {
        return view.groups().stream()
                .flatMap(group -> group.projects().stream())
                .flatMap(project -> project.tasks().stream())
                .filter(task -> task.id() == id)
                .findFirst()
                .orElseThrow(() -> new AssertionError("任务未出现在甘特中: " + id));
    }
}
