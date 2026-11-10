package com.pmo.supervise.task;

import com.pmo.supervise.common.PageResponse;
import com.pmo.supervise.task.dto.TaskCounters;
import com.pmo.supervise.task.dto.TaskDetailRow;
import com.pmo.supervise.task.dto.TaskEventRow;
import com.pmo.supervise.task.dto.TaskFilter;
import com.pmo.supervise.task.dto.TaskRow;
import com.pmo.supervise.task.dto.UserRef;
import com.pmo.supervise.common.Role;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 原生 SQL 读模型（TECHNICAL §4.4）：分页、详情、焦点、KPI 计数。
 * 聚合与 is_overdue 只在这一层算，任何页面都拿不到第二套数字。
 */
@Repository
public class TaskQueryDao {

    private static final String SELECT_BASE = """
            SELECT t.id, t.title, t.owner_id, u.name AS owner_name, u.role AS owner_role,
                   t.due_date, t.source_type, st.name AS source_name, st.color AS source_color,
                   st.form AS source_form,
                   t.source_meeting_id, m.title AS meeting_title,
                   t.source_decision_item_id, t.source_note,
                   t.source_project_id, p.name AS project_name, p.enabled AS project_enabled,
                   t.status, t.priority,
                   t.dispatcher_id, d.name AS dispatcher_name, d.role AS dispatcher_role,
                   t.completion_note, t.completion_url, t.submitted_at, t.completed_at,
                   t.created_at, t.updated_at,
                   CASE WHEN %s THEN 1 ELSE 0 END AS is_overdue,
                   CASE WHEN %s THEN DATEDIFF(:today, t.due_date) ELSE 0 END AS overdue_days,
                   CASE WHEN %s THEN 1 ELSE 0 END AS due_soon,
                   (SELECT COUNT(*) FROM urge_records ur WHERE ur.task_id = t.id) AS urge_count,
                   (SELECT MAX(ur.created_at) FROM urge_records ur WHERE ur.task_id = t.id) AS last_urged_at
            FROM tasks t
            JOIN users u ON u.id = t.owner_id
            JOIN users d ON d.id = t.dispatcher_id
            LEFT JOIN meetings m ON m.id = t.source_meeting_id
            LEFT JOIN source_types st ON st.code = t.source_type
            LEFT JOIN projects p ON p.id = t.source_project_id
            """.formatted(TaskSql.OVERDUE, TaskSql.OVERDUE, TaskSql.DUE_SOON);

    private static final String COUNT_BASE = """
            SELECT COUNT(*)
            FROM tasks t
            JOIN users u ON u.id = t.owner_id
            """;

    private final NamedParameterJdbcTemplate jdbc;
    private final TaskQueryBuilder queryBuilder;

    public TaskQueryDao(NamedParameterJdbcTemplate jdbc, TaskQueryBuilder queryBuilder) {
        this.jdbc = jdbc;
        this.queryBuilder = queryBuilder;
    }

    public PageResponse<TaskRow> page(TaskFilter filter, LocalDate today, int page, int size) {
        int safePage = Math.max(page, 1);
        int safeSize = Math.min(Math.max(size, 1), 100);
        TaskQueryBuilder.TaskQuery query = queryBuilder.build(filter, today);

        Long total = jdbc.queryForObject(COUNT_BASE + query.where(), query.params(), Long.class);
        Map<String, Object> params = new LinkedHashMap<>(query.params());
        params.put("limit", safeSize);
        params.put("offset", (long) (safePage - 1) * safeSize);
        List<TaskRow> rows = jdbc.query(
                SELECT_BASE + query.where() + " " + query.orderBy() + " LIMIT :limit OFFSET :offset",
                params, (rs, rowNum) -> TaskRowMapper.mapRow(rs));
        return PageResponse.of(rows, total == null ? 0 : total, safePage, safeSize);
    }

    public Optional<TaskDetailRow> findDetail(long id, LocalDate today) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("today", today);
        params.put("tomorrow", today.plusDays(1));
        params.put("id", id);
        List<TaskDetailRow> rows = jdbc.query(
                SELECT_BASE + " WHERE t.id = :id", params, (rs, rowNum) -> TaskRowMapper.mapDetail(rs));
        return rows.stream().findFirst();
    }

    /**
     * 任务行读模型的唯一列集 + join（含 source_types 取名取色）。
     * 看板焦点列表也复用同一份，来源 join 不会两处各拼一次而漂移。
     */
    public static String selectBase() {
        return SELECT_BASE;
    }

    /** 焦点列表：未完成优先，逾期在前，其次按截止日升序。 */
    public List<TaskRow> focus(TaskFilter filter, LocalDate today, int limit) {
        int safeLimit = Math.min(Math.max(limit, 1), 50);
        TaskQueryBuilder.TaskQuery query = queryBuilder.build(filter, today);
        Map<String, Object> params = new LinkedHashMap<>(query.params());
        params.put("limit", safeLimit);
        String sql = SELECT_BASE + query.where() + " AND " + TaskSql.NOT_FINISHED
                + " ORDER BY (CASE WHEN " + TaskSql.OVERDUE + " THEN 1 ELSE 0 END) DESC,"
                + " t.due_date ASC, t.id DESC LIMIT :limit";
        return jdbc.query(sql, params, (rs, rowNum) -> TaskRowMapper.mapRow(rs));
    }

    /** 看板 6 项 KPI 的唯一计数口径。 */
    public TaskCounters counters(TaskFilter filter, LocalDate today) {
        TaskQueryBuilder.TaskQuery query = queryBuilder.build(filter, today);
        String sql = """
                SELECT COUNT(*) AS total,
                       COALESCE(SUM(CASE WHEN %s THEN 1 ELSE 0 END), 0) AS unfinished,
                       COALESCE(SUM(CASE WHEN %s THEN 1 ELSE 0 END), 0) AS overdue,
                       COALESCE(SUM(CASE WHEN %s THEN 1 ELSE 0 END), 0) AS due_soon,
                       COALESCE(SUM(CASE WHEN t.status = 'PENDING_ACCEPT' THEN 1 ELSE 0 END), 0) AS pending_accept,
                       COALESCE(SUM(CASE WHEN t.status = 'DONE' THEN 1 ELSE 0 END), 0) AS done
                FROM tasks t
                JOIN users u ON u.id = t.owner_id
                """.formatted(TaskSql.NOT_FINISHED, TaskSql.OVERDUE, TaskSql.DUE_SOON);
        return jdbc.queryForObject(sql + query.where(), query.params(), (rs, rowNum) -> new TaskCounters(
                rs.getLong("total"),
                rs.getLong("unfinished"),
                rs.getLong("overdue"),
                rs.getLong("due_soon"),
                rs.getLong("pending_accept"),
                rs.getLong("done")));
    }

    public List<TaskEventRow> events(long taskId) {
        String sql = """
                SELECT e.id, e.action, e.actor_id, a.name AS actor_name, a.role AS actor_role,
                       e.from_status, e.to_status, e.reason, e.created_at
                FROM task_events e
                JOIN users a ON a.id = e.actor_id
                WHERE e.task_id = :taskId
                ORDER BY e.created_at ASC, e.id ASC
                """;
        List<Map<String, Object>> rows = jdbc.queryForList(sql, Map.of("taskId", taskId));
        List<TaskEventRow> events = new ArrayList<>(rows.size());
        for (Map<String, Object> row : rows) {
            String action = (String) row.get("action");
            events.add(new TaskEventRow(
                    ((Number) row.get("id")).longValue(),
                    action,
                    actionLabel(action),
                    new UserRef(((Number) row.get("actor_id")).longValue(),
                            (String) row.get("actor_name"),
                            Role.of((String) row.get("actor_role"))),
                    (String) row.get("from_status"),
                    (String) row.get("to_status"),
                    (String) row.get("reason"),
                    toLocalDateTime(row.get("created_at"))));
        }
        return events;
    }

    private String actionLabel(String action) {
        try {
            return TaskAction.of(action).label();
        } catch (IllegalArgumentException ex) {
            return action;
        }
    }

    private static java.time.LocalDateTime toLocalDateTime(Object value) {
        if (value instanceof java.sql.Timestamp timestamp) {
            return timestamp.toLocalDateTime();
        }
        if (value instanceof java.time.LocalDateTime dateTime) {
            return dateTime;
        }
        if (value instanceof java.time.LocalDate date) {
            return date.atStartOfDay();
        }
        return null;
    }
}