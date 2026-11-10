package com.pmo.supervise.dashboard;

import com.pmo.supervise.common.Actor;
import com.pmo.supervise.common.BusinessException;
import com.pmo.supervise.common.Role;
import com.pmo.supervise.common.ShanghaiClock;
import com.pmo.supervise.common.UserStatus;
import com.pmo.supervise.dashboard.dto.MatrixFilter;
import com.pmo.supervise.dashboard.dto.MatrixRow;
import com.pmo.supervise.dashboard.dto.MatrixView;
import com.pmo.supervise.source.SourceTypeService;
import com.pmo.supervise.task.TaskSql;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 人维度闭环矩阵（TECHNICAL §4.9 / AC-15）。
 *
 * <p>五列互斥：逾期任务只计入 overdue，不再出现在原状态列；cancelled 单列统计、不进入五列。
 * 所有计数与逾期判定复用 {@link TaskSql#OVERDUE}，today 由调用方下传，SQL 不出现 CURDATE()，
 * 因此矩阵与任务池、看板在同一时刻必然得到同一个逾期结论（AC-17）。
 */
@Service
public class MatrixService {

    /** 三个原状态列都要求「未逾期」，逾期列用同一段 TaskSql.OVERDUE，保证互斥而非近似。 */
    private static final String MATRIX_SQL = """
            SELECT u.id AS user_id, u.name AS name, u.role AS role, u.status AS user_status,
                   COALESCE(SUM(CASE WHEN t.status = 'PENDING' AND NOT %s THEN 1 ELSE 0 END), 0) AS pending,
                   COALESCE(SUM(CASE WHEN t.status = 'IN_PROGRESS' AND NOT %s THEN 1 ELSE 0 END), 0) AS in_progress,
                   COALESCE(SUM(CASE WHEN t.status = 'PENDING_ACCEPT' AND NOT %s THEN 1 ELSE 0 END), 0) AS pending_accept,
                   COALESCE(SUM(CASE WHEN %s THEN 1 ELSE 0 END), 0) AS overdue,
                   COALESCE(SUM(CASE WHEN t.status = 'DONE' THEN 1 ELSE 0 END), 0) AS done,
                   COALESCE(SUM(CASE WHEN t.status = 'CANCELLED' THEN 1 ELSE 0 END), 0) AS cancelled
            FROM tasks t
            JOIN users u ON u.id = t.owner_id
            WHERE 1 = 1
            """.formatted(TaskSql.OVERDUE, TaskSql.OVERDUE, TaskSql.OVERDUE, TaskSql.OVERDUE);

    private static final String MATRIX_TAIL = """
             GROUP BY u.id, u.name, u.role, u.status
            HAVING SUM(CASE WHEN t.status <> 'CANCELLED' THEN 1 ELSE 0 END) > 0
            """;

    /** 默认排序：逾期数降序 → 行合计降序 → 姓名稳定排序（DESIGN §4.8）。 */
    private static final Comparator<MatrixRow> DEFAULT_ORDER =
            Comparator.comparingLong(MatrixRow::overdue).reversed()
                    .thenComparing(Comparator.comparingLong(MatrixRow::total).reversed())
                    .thenComparing(MatrixRow::name);

    private final NamedParameterJdbcTemplate jdbc;
    private final SourceTypeService sourceTypes;

    public MatrixService(NamedParameterJdbcTemplate jdbc, SourceTypeService sourceTypes) {
        this.jdbc = jdbc;
        this.sourceTypes = sourceTypes;
    }

    /** HTTP 入口：仅管理员 / 管理层可读（AC-16）。 */
    public MatrixView load(MatrixFilter filter, LocalDate today, Actor actor) {
        requireGlobalRead(actor);
        return load(filter, today);
    }

    /** 内部复用入口（蓝图签名）；HTTP 入口必须走带 Actor 的重载。 */
    public MatrixView load(MatrixFilter filter, LocalDate today) {
        MatrixFilter safeFilter = filter == null ? new MatrixFilter() : filter;
        LocalDate effectiveToday = today == null ? LocalDate.now(ShanghaiClock.ZONE) : today;

        String sourceType = resolveSourceType(safeFilter.getSourceType());
        DateRange range = resolveRange(safeFilter.getRange(), effectiveToday);
        boolean activeOnly = Boolean.FALSE.equals(safeFilter.getIncludeDisabled());

        Map<String, Object> params = new LinkedHashMap<>();
        params.put("today", effectiveToday);
        StringBuilder sql = new StringBuilder(MATRIX_SQL);
        if (sourceType != null) {
            sql.append(" AND t.source_type = :sourceType");
            params.put("sourceType", sourceType);
        }
        if (range != null) {
            sql.append(" AND t.due_date >= :rangeFrom AND t.due_date <= :rangeTo");
            params.put("rangeFrom", range.from());
            params.put("rangeTo", range.to());
        }
        if (activeOnly) {
            sql.append(" AND u.status = 'ACTIVE'");
        }
        sql.append(MATRIX_TAIL);

        List<MatrixRow> rows = new ArrayList<>(jdbc.query(sql.toString(), params, (rs, rowNum) -> {
            long pending = rs.getLong("pending");
            long inProgress = rs.getLong("in_progress");
            long pendingAccept = rs.getLong("pending_accept");
            long overdue = rs.getLong("overdue");
            long done = rs.getLong("done");
            long cancelled = rs.getLong("cancelled");
            long total = pending + inProgress + pendingAccept + overdue + done;
            return new MatrixRow(
                    rs.getLong("user_id"),
                    rs.getString("name"),
                    Role.of(rs.getString("role")),
                    UserStatus.of(rs.getString("user_status")),
                    pending, inProgress, pendingAccept, overdue, done, cancelled, total,
                    completionRate(done, total));
        }));
        rows.sort(DEFAULT_ORDER);

        long pending = rows.stream().mapToLong(MatrixRow::pending).sum();
        long inProgress = rows.stream().mapToLong(MatrixRow::inProgress).sum();
        long pendingAccept = rows.stream().mapToLong(MatrixRow::pendingAccept).sum();
        long overdue = rows.stream().mapToLong(MatrixRow::overdue).sum();
        long done = rows.stream().mapToLong(MatrixRow::done).sum();
        long cancelled = rows.stream().mapToLong(MatrixRow::cancelled).sum();
        long total = pending + inProgress + pendingAccept + overdue + done;
        // 合计行由各行相加得到（不是第二遍 SQL），保证页面上的列合计永远等于各行之和。
        MatrixRow totals = new MatrixRow(0L, "合计", null, null,
                pending, inProgress, pendingAccept, overdue, done, cancelled, total,
                completionRate(done, total));

        return new MatrixView(effectiveToday,
                range == null ? null : range.from(),
                range == null ? null : range.to(),
                rows, totals);
    }

    private static void requireGlobalRead(Actor actor) {
        if (actor == null || !actor.isGlobalReader()) {
            throw BusinessException.forbidden("需要管理员或管理层权限");
        }
    }

    /** 来源筛选与任务池同源校验：未知编码 400，停用来源仍可筛历史（AC-5）。 */
    private String resolveSourceType(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        return sourceTypes.requireKnown(raw.trim()).code();
    }

    /** 本周从周一起算；本月为 1 号至月末；按 due_date 过滤（AC-15）。 */
    private static DateRange resolveRange(String raw, LocalDate today) {
        String value = (raw == null || raw.isBlank()) ? "ALL" : raw.trim().toUpperCase(Locale.ROOT);
        return switch (value) {
            case "ALL" -> null;
            case "WEEK" -> {
                LocalDate monday = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
                yield new DateRange(monday, monday.plusDays(6));
            }
            case "MONTH" -> new DateRange(today.withDayOfMonth(1),
                    today.with(TemporalAdjusters.lastDayOfMonth()));
            default -> throw BusinessException.validation("range", "时间范围不合法，可选 WEEK / MONTH / ALL");
        };
    }

    private static Double completionRate(long done, long total) {
        if (total == 0) {
            return null;
        }
        return BigDecimal.valueOf(done)
                .divide(BigDecimal.valueOf(total), 4, RoundingMode.HALF_UP)
                .doubleValue();
    }

    private record DateRange(LocalDate from, LocalDate to) {
    }
}
