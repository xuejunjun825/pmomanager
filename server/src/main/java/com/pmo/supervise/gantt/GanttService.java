package com.pmo.supervise.gantt;

import com.pmo.supervise.common.Actor;
import com.pmo.supervise.common.BusinessException;
import com.pmo.supervise.common.ShanghaiClock;
import com.pmo.supervise.gantt.dto.GanttQuery;
import com.pmo.supervise.gantt.dto.GanttView;
import com.pmo.supervise.task.TaskQueryDao;
import com.pmo.supervise.task.dto.TaskCounters;
import com.pmo.supervise.task.dto.TaskFilter;
import com.pmo.supervise.task.dto.TaskRow;
import com.pmo.supervise.task.dto.TaskSource;
import com.pmo.supervise.task.dto.ThemeRef;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 甘特聚合（TECHNICAL §3.4 / AC-7 / AC-10 / AC-12）。
 *
 * <p>一个只读入口把整张图装好：窗口校验 → 查询（逾期/风险绕出窗口）→ 裁剪到 300 条 →
 * 主题/项目分组 → 摘要。摘要的逾期/风险走 {@link TaskQueryDao#counters}，与看板是同一个函数，
 * 因此「看板说有 5 条风险，图上一定找得到 5 条」（AC-7）。
 */
@Service
public class GanttService {

    /** 窗口上限：再宽就会把整库任务拉进一棵树里。 */
    public static final int MAX_WINDOW_DAYS = 400;

    private final GanttDao dao;
    private final TaskQueryDao taskQueryDao;

    public GanttService(GanttDao dao, TaskQueryDao taskQueryDao) {
        this.dao = dao;
        this.taskQueryDao = taskQueryDao;
    }

    public GanttView load(Actor actor, LocalDate today, GanttQuery query) {
        requireGlobalRead(actor);
        LocalDate day = today == null ? LocalDate.now(ShanghaiClock.ZONE) : today;
        GanttQuery q = query == null ? new GanttQuery(null, null, null, true, false, null) : query;
        validate(q);

        List<TaskRow> probed = dao.query(q, day, GanttDao.PROBE_LIMIT);
        boolean truncated = probed.size() > GanttDao.MAX_ROWS;
        List<TaskRow> visible = truncated ? List.copyOf(probed.subList(0, GanttDao.MAX_ROWS)) : probed;

        Map<Long, LocalDate> createdDates = dao.createdDates(visible.stream()
                .filter(row -> row.plannedStartDate() == null)
                .map(TaskRow::id)
                .toList());
        GanttDao.WindowCounts counts = dao.count(q, day);
        TaskCounters global = taskQueryDao.counters(globalFilter(q), day);

        GanttView.Summary summary = new GanttView.Summary(
                global.overdue(), global.risk(),
                counts.inProgress(), counts.pendingAccept(), counts.done(), counts.unassigned());
        return new GanttView(day, q.from(), q.to(), truncated, (int) counts.total(),
                summary, groups(visible, q, createdDates));
    }

    /** from/to 是查询参数的语义校验：422 字段级，前端能直接指到出错的输入框。 */
    private static void validate(GanttQuery query) {
        if (query.from() == null) {
            throw BusinessException.validation("from", "开始日期不能为空");
        }
        if (query.to() == null) {
            throw BusinessException.validation("to", "结束日期不能为空");
        }
        if (query.from().isAfter(query.to())) {
            throw BusinessException.validation("from", "开始日期不能晚于结束日期");
        }
        long days = ChronoUnit.DAYS.between(query.from(), query.to()) + 1;
        if (days > MAX_WINDOW_DAYS) {
            throw BusinessException.validation("to", "时间范围最多 " + MAX_WINDOW_DAYS + " 天");
        }
    }

    /** 摘要的全局口径只带 owner/theme：与看板 counters 同样的筛选，不含窗口与 onlyRisk。 */
    private static TaskFilter globalFilter(GanttQuery query) {
        TaskFilter filter = new TaskFilter();
        filter.setOwnerId(query.ownerId());
        filter.setTheme(query.theme());
        return filter;
    }

    /** 分组按 SQL 首次出现顺序（LinkedHashMap），未归主题/未指定项目由 SQL 排在最后。 */
    private static List<GanttView.ThemeGroup> groups(List<TaskRow> rows, GanttQuery query,
                                                      Map<Long, LocalDate> createdDates) {
        Map<Long, ThemeAcc> themes = new LinkedHashMap<>();
        for (TaskRow row : rows) {
            ThemeRef theme = row.theme();
            Long themeId = theme == null ? null : theme.id();
            ThemeAcc acc = themes.computeIfAbsent(themeId, id -> new ThemeAcc(theme));
            acc.add(row, query, createdDates);
        }
        List<GanttView.ThemeGroup> groups = new ArrayList<>(themes.size());
        for (ThemeAcc acc : themes.values()) {
            groups.add(acc.toGroup());
        }
        return groups;
    }

    /**
     * 条的一端被窗口裁掉时置 pin：左侧（起点早于窗口）与右侧（截止晚于窗口）分开标，
     * 前端据此画虚线帽/三角夹标；判定只在这里做一次，组件不比较日期。
     */
    private static GanttView.Task toTask(TaskRow row, GanttQuery query, Map<Long, LocalDate> createdDates) {
        LocalDate planned = row.plannedStartDate();
        LocalDate start = planned != null ? planned : createdDates.get(row.id());
        boolean estimated = planned == null;
        if (start == null) {
            // 兜底：created_at 理论上不会缺；真缺时退化到截止日，条至少还画得出来。
            start = row.dueDate();
        }
        ThemeRef theme = row.theme();
        TaskSource source = row.source();
        return new GanttView.Task(
                row.id(),
                row.title(),
                row.owner(),
                row.status() == null ? null : row.status().name(),
                row.statusLabel(),
                start,
                planned,
                row.dueDate(),
                estimated,
                row.isOverdue(),
                row.overdueDays(),
                row.atRisk(),
                start.isBefore(query.from()),
                row.dueDate() != null && row.dueDate().isAfter(query.to()),
                source == null ? null : source.projectId(),
                source == null ? null : source.projectName(),
                source == null ? null : source.typeLabel(),
                source == null ? null : source.color());
    }

    private static void requireGlobalRead(Actor actor) {
        if (actor == null || !actor.isGlobalReader()) {
            throw BusinessException.forbidden("需要管理员或管理层权限");
        }
    }

    /** 主题累加器：主行计数与项目子行都在累加时算好，避免二次遍历。 */
    private static final class ThemeAcc {

        private final ThemeRef theme;
        private final Map<Long, List<GanttView.Task>> projects = new LinkedHashMap<>();
        private final Map<Long, String> projectNames = new LinkedHashMap<>();
        private long taskCount;
        private long overdueCount;
        private long riskCount;

        private ThemeAcc(ThemeRef theme) {
            this.theme = theme;
        }

        private void add(TaskRow row, GanttQuery query, Map<Long, LocalDate> createdDates) {
            GanttView.Task task = toTask(row, query, createdDates);
            projects.computeIfAbsent(task.projectId(), id -> new ArrayList<>()).add(task);
            projectNames.putIfAbsent(task.projectId(), task.projectName());
            taskCount++;
            if (task.isOverdue()) {
                overdueCount++;
            }
            if (task.isAtRisk()) {
                riskCount++;
            }
        }

        private GanttView.ThemeGroup toGroup() {
            List<GanttView.ProjectGroup> projectGroups = new ArrayList<>(projects.size());
            projects.forEach((projectId, tasks) -> projectGroups.add(new GanttView.ProjectGroup(
                    projectId,
                    projectNames.get(projectId),
                    projectId == null,
                    tasks.size(),
                    List.copyOf(tasks))));
            boolean unassigned = theme == null;
            return new GanttView.ThemeGroup(
                    unassigned ? null : theme.id(),
                    unassigned ? "未归主题" : theme.name(),
                    unassigned ? "" : theme.color(),
                    unassigned ? "" : theme.softColor(),
                    unassigned ? "" : theme.borderColor(),
                    unassigned || theme.enabled(),
                    unassigned,
                    taskCount,
                    overdueCount,
                    riskCount,
                    List.copyOf(projectGroups));
        }
    }
}
