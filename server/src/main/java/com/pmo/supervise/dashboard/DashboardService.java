package com.pmo.supervise.dashboard;

import com.pmo.supervise.common.Actor;
import com.pmo.supervise.common.BusinessException;
import com.pmo.supervise.common.ShanghaiClock;
import com.pmo.supervise.dashboard.dto.DashboardView;
import com.pmo.supervise.dashboard.dto.Kpis;
import com.pmo.supervise.task.TaskQueryBuilder;
import com.pmo.supervise.task.TaskQueryDao;
import com.pmo.supervise.task.TaskRowMapper;
import com.pmo.supervise.task.TaskSql;
import com.pmo.supervise.task.dto.TaskCounters;
import com.pmo.supervise.task.dto.TaskFilter;
import com.pmo.supervise.task.dto.TaskRow;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 管理层看板（TECHNICAL §4.9 / AC-16）。
 *
 * <p>KPI 只从 {@link TaskQueryDao#counters} 取数：total = 非取消（unfinished + done），
 * unfinished 直接就是四个互斥桶之和，逾期不重复计（Anti-goal #1）。
 * 焦点列表与任务池共用 {@link TaskQueryBuilder} 的 WHERE，仅排序按四段桶重写。
 */
@Service
public class DashboardService {

    private static final int DEFAULT_FOCUS_LIMIT = 10;
    private static final int MAX_FOCUS_LIMIT = 50;

    /**
     * 四段桶（AC-16）：逾期 → 待开始（截止日升序）→ 待验收（提交时间升序）→ 进行中（截止日升序）。
     * 逾期段内按逾期天数降序；其余段内沿用截止日升序作为稳定次序。
     */
    private static final String FOCUS_ORDER = """
             ORDER BY CASE WHEN %s THEN 0
                           WHEN t.status = 'PENDING' THEN 1
                           WHEN t.status = 'PENDING_ACCEPT' THEN 2
                           ELSE 3 END ASC,
                      CASE WHEN %s THEN DATEDIFF(:today, t.due_date) ELSE 0 END DESC,
                      CASE WHEN t.status = 'PENDING_ACCEPT' AND NOT %s THEN t.submitted_at END ASC,
                      t.due_date ASC, t.id DESC
            """.formatted(TaskSql.OVERDUE, TaskSql.OVERDUE, TaskSql.OVERDUE);

    private final TaskQueryDao queryDao;
    private final TaskQueryBuilder queryBuilder;
    private final NamedParameterJdbcTemplate jdbc;

    public DashboardService(TaskQueryDao queryDao, TaskQueryBuilder queryBuilder,
                            NamedParameterJdbcTemplate jdbc) {
        this.queryDao = queryDao;
        this.queryBuilder = queryBuilder;
        this.jdbc = jdbc;
    }

    /** HTTP 入口：仅管理员 / 管理层可读（AC-16）。 */
    public DashboardView load(TaskFilter filter, LocalDate today, int focusLimit, Actor actor) {
        requireGlobalRead(actor);
        return load(filter, today, focusLimit);
    }

    /** 内部复用入口（蓝图签名，日报 T11 直接调用）；HTTP 入口必须走带 Actor 的重载。 */
    public DashboardView load(TaskFilter filter, LocalDate today, int focusLimit) {
        TaskFilter safeFilter = filter == null ? new TaskFilter() : filter;
        LocalDate effectiveToday = today == null ? LocalDate.now(ShanghaiClock.ZONE) : today;
        int safeLimit = Math.min(Math.max(focusLimit <= 0 ? DEFAULT_FOCUS_LIMIT : focusLimit, 1), MAX_FOCUS_LIMIT);

        TaskCounters counters = queryDao.counters(safeFilter, effectiveToday);
        long total = counters.unfinished() + counters.done();
        long unfinished = counters.unfinished();
        Kpis kpis = new Kpis(
                total,
                unfinished,
                counters.overdue(),
                ratio(counters.overdue(), unfinished),
                countDueSoon24h(safeFilter, effectiveToday),
                counters.pendingAccept());

        return new DashboardView(effectiveToday, kpis, focus(safeFilter, effectiveToday, safeLimit), unfinished);
    }

    /** 日报正文（T11 调用）：与看板同源，直接取 load() 的结果，不重复聚合。 */
    public String buildDailyMarkdown(LocalDate today) {
        LocalDate effectiveToday = today == null ? LocalDate.now(ShanghaiClock.ZONE) : today;
        DashboardView view = load(new TaskFilter(), effectiveToday, DEFAULT_FOCUS_LIMIT);
        Kpis kpis = view.kpis();

        StringBuilder md = new StringBuilder();
        md.append("### 会议任务督办日报 · ").append(effectiveToday).append('\n');
        md.append("> 口径与看板一致：逾期任务只计一次，未完成 = 待开始 + 进行中 + 待验收 + 逾期。\n\n");
        md.append("- 任务总数（非取消）：").append(kpis.total()).append('\n');
        md.append("- 未完成：").append(kpis.unfinished()).append('\n');
        md.append("- 逾期：").append(kpis.overdue())
                .append("（逾期率 ").append(formatPercent(kpis.overdueRate())).append("）\n");
        md.append("- 24 小时内到期：").append(kpis.dueSoon24h()).append('\n');
        md.append("- 待验收：").append(kpis.pendingAccept()).append('\n');
        md.append("- 今日新增逾期：").append(countNewlyOverdue(effectiveToday)).append('\n');

        md.append("\n**督办焦点（前 ").append(view.focus().size()).append(" 条）**\n\n");
        if (view.focus().isEmpty()) {
            md.append("无未完成任务。\n");
        } else {
            int index = 1;
            for (TaskRow row : view.focus()) {
                md.append(index++).append(". ").append(row.title())
                        .append("｜").append(row.owner().name())
                        .append("｜截止 ").append(row.dueDate())
                        .append("｜").append(row.statusLabel());
                if (row.isOverdue()) {
                    md.append("｜已逾期 ").append(row.overdueDays()).append(" 天");
                }
                md.append('\n');
            }
        }
        return md.toString();
    }

    private List<TaskRow> focus(TaskFilter filter, LocalDate today, int limit) {
        TaskQueryBuilder.TaskQuery query = queryBuilder.build(filter, today);
        Map<String, Object> params = new LinkedHashMap<>(query.params());
        params.put("limit", limit);
        // 列集与来源 join 复用任务池的 SELECT_BASE：两处拼 SQL 迟早漂移（TECHNICAL 风险项）。
        String sql = TaskQueryDao.selectBase() + query.where() + " AND " + TaskSql.NOT_FINISHED
                + FOCUS_ORDER + " LIMIT :limit";
        return jdbc.query(sql, params, (rs, rowNum) -> TaskRowMapper.mapRow(rs));
    }

    /**
     * 24 小时内到期：未完成且 due_date ∈ {today, today+1}（TECHNICAL §5.5）。
     * TaskQueryDao.counters 的 dueSoon24h 目前只统计明天，故此处按全文口径单算，见 OUT-OF-SCOPE。
     */
    private long countDueSoon24h(TaskFilter filter, LocalDate today) {
        TaskQueryBuilder.TaskQuery query = queryBuilder.build(filter, today);
        String sql = "SELECT COUNT(*) FROM tasks t JOIN users u ON u.id = t.owner_id"
                + query.where() + " AND " + TaskSql.NOT_FINISHED
                + " AND t.due_date >= :today AND t.due_date <= :tomorrow";
        Long count = jdbc.queryForObject(sql, query.params(), Long.class);
        return count == null ? 0 : count;
    }

    private long countNewlyOverdue(LocalDate today) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("yesterday", today.minusDays(1));
        Long count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM tasks t WHERE t.due_date = :yesterday AND " + TaskSql.NOT_FINISHED,
                params, Long.class);
        return count == null ? 0 : count;
    }

    private static void requireGlobalRead(Actor actor) {
        if (actor == null || !actor.isGlobalReader()) {
            throw BusinessException.forbidden("需要管理员或管理层权限");
        }
    }

    private static double ratio(long numerator, long denominator) {
        if (denominator == 0) {
            return 0.0;
        }
        return BigDecimal.valueOf(numerator)
                .divide(BigDecimal.valueOf(denominator), 4, RoundingMode.HALF_UP)
                .doubleValue();
    }

    private static String formatPercent(double rate) {
        return BigDecimal.valueOf(rate * 100)
                .setScale(1, RoundingMode.HALF_UP)
                .toPlainString() + "%";
    }
}
