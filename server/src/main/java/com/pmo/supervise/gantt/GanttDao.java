package com.pmo.supervise.gantt;

import com.pmo.supervise.gantt.dto.GanttQuery;
import com.pmo.supervise.task.TaskQueryDao;
import com.pmo.supervise.task.TaskRowMapper;
import com.pmo.supervise.task.TaskSql;
import com.pmo.supervise.task.ThemeFilterSql;
import com.pmo.supervise.task.dto.TaskRow;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 甘特的窗口查询（TECHNICAL §3.4 / AC-7 / AC-10 / AC-12）。
 *
 * <p>三件事只在这里做一次：①窗口谓词写成「逾期∪风险 OR 与窗口相交」，
 * 所以逾期任务无论多老都会被返回（前端贴左边界 pin），右界外的风险任务也不会消失；
 * ②排序把主题/项目按 sort_no 排好、未归主题与未指定项目置底，Service 直接顺序分组；
 * ③多取一条（{@value #PROBE_LIMIT}）探测截断，返回给上层前由 Service 丢掉第 301 条。
 *
 * <p>行映射复用 {@code TaskQueryDao.selectBase()} 与 {@link TaskRowMapper}，
 * 甘特不写第二个行映射器——主题三色、atRisk、plannedStartDate 不会在两处漂移。
 */
@Component
public class GanttDao {

    /** 单次返回上限（AC-12）：超过即 truncated=true。 */
    public static final int MAX_ROWS = 300;

    /** 多取 1 条只为判断「是否还有第 301 条」，不返回给前端。 */
    public static final int PROBE_LIMIT = MAX_ROWS + 1;

    private static final String ORDER_BY = """
            ORDER BY (t.theme_id IS NULL) ASC, th.sort_no ASC, th.id ASC,
                     (t.source_project_id IS NULL) ASC, p.sort_no ASC, p.id ASC,
                     CASE WHEN %s THEN 0 WHEN %s THEN 1
                          WHEN t.status = 'IN_PROGRESS' THEN 2
                          WHEN t.status = 'PENDING_ACCEPT' THEN 3
                          WHEN t.status = 'PENDING' THEN 4 ELSE 5 END ASC,
                     t.due_date ASC, t.id DESC""".formatted(TaskSql.OVERDUE, TaskSql.AT_RISK);

    private final NamedParameterJdbcTemplate jdbc;
    private final ThemeFilterSql themeFilter;

    public GanttDao(NamedParameterJdbcTemplate jdbc, ThemeFilterSql themeFilter) {
        this.jdbc = jdbc;
        this.themeFilter = themeFilter;
    }

    /** 窗口内任务（含窗口外的逾期/风险任务），按主题→项目→状态排序。 */
    public List<TaskRow> query(GanttQuery query, LocalDate today, int limit) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("today", today);
        // SELECT_BASE 里的 due_soon 列固定引用 :tomorrow，与 TaskQueryBuilder 保持同一套参数。
        params.put("tomorrow", today.plusDays(1));
        String where = where(query, params);
        params.put("limit", limit);
        String sql = TaskQueryDao.selectBase() + where + " " + ORDER_BY + " LIMIT :limit";
        return jdbc.query(sql, params, (rs, rowNum) -> TaskRowMapper.mapRow(rs));
    }

    /** 与 {@link #query} 同一个 WHERE 的窗口计数（不含 LIMIT），用于 windowTotal 与摘要四数。 */
    public WindowCounts count(GanttQuery query, LocalDate today) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("today", today);
        String where = where(query, params);
        String sql = """
                SELECT COUNT(*) AS total,
                       COALESCE(SUM(CASE WHEN t.status = 'IN_PROGRESS' THEN 1 ELSE 0 END), 0) AS in_progress,
                       COALESCE(SUM(CASE WHEN t.status = 'PENDING_ACCEPT' THEN 1 ELSE 0 END), 0) AS pending_accept,
                       COALESCE(SUM(CASE WHEN t.status = 'DONE' THEN 1 ELSE 0 END), 0) AS done,
                       COALESCE(SUM(CASE WHEN t.theme_id IS NULL THEN 1 ELSE 0 END), 0) AS unassigned
                FROM tasks t
                JOIN users u ON u.id = t.owner_id
                """ + where;
        return jdbc.queryForObject(sql, params, (rs, rowNum) -> new WindowCounts(
                rs.getLong("total"),
                rs.getLong("in_progress"),
                rs.getLong("pending_accept"),
                rs.getLong("done"),
                rs.getLong("unassigned")));
    }

    /**
     * 创建日回退（TECHNICAL §2.1：planned_start_date 为空时用 DATE(created_at) 推算并标 estimatedStart）。
     *
     * <p>TaskRow 不带 createdAt（它是任务池/详情的行类型，甘特不擅自加字段），
     * 因此这里按 id 批量补一次小查询——仍然不新建行映射器，也不改 task 包。
     */
    public Map<Long, LocalDate> createdDates(List<Long> taskIds) {
        if (taskIds == null || taskIds.isEmpty()) {
            return Map.of();
        }
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT id, DATE(created_at) AS created_date FROM tasks WHERE id IN (:ids)",
                Map.of("ids", taskIds));
        Map<Long, LocalDate> dates = new HashMap<>(rows.size() * 2);
        for (Map<String, Object> row : rows) {
            dates.put(((Number) row.get("id")).longValue(), toLocalDate(row.get("created_date")));
        }
        return dates;
    }

    /**
     * 窗口谓词：逾期∪风险无条件返回，其余要求与窗口相交。
     * 与 {@link TaskSql#AT_RISK} 同一个定义，风险不会在「图上能不能找到」这一层再写一遍。
     */
    private String where(GanttQuery query, Map<String, Object> params) {
        params.put("from", query.from());
        params.put("to", query.to());
        StringBuilder where = new StringBuilder(" WHERE 1 = 1");
        if (query.ownerId() != null) {
            where.append(" AND t.owner_id = :ownerId");
            params.put("ownerId", query.ownerId());
        }
        if (!query.includeDone()) {
            where.append(" AND t.status <> 'DONE'");
        }
        if (query.onlyRisk()) {
            where.append(" AND ").append(TaskSql.AT_RISK);
        }
        // 主题谓词只有一份：任务池与甘特都走 ThemeFilterSql（AC-13「同源」）。
        themeFilter.append(where, params, query.theme());
        where.append(" AND (").append(TaskSql.AT_RISK)
                .append(" OR (COALESCE(t.planned_start_date, DATE(t.created_at)) <= :to")
                .append(" AND t.due_date >= :from))");
        return where.toString();
    }

    private static LocalDate toLocalDate(Object value) {
        if (value instanceof java.sql.Date date) {
            return date.toLocalDate();
        }
        if (value instanceof LocalDate date) {
            return date;
        }
        if (value instanceof java.sql.Timestamp timestamp) {
            return timestamp.toLocalDateTime().toLocalDate();
        }
        return null;
    }

    /** 与 {@link #query} 同 WHERE 的窗口计数；摘要四数与 windowTotal 由它而来。 */
    public record WindowCounts(long total, long inProgress, long pendingAccept, long done, long unassigned) {
    }
}
