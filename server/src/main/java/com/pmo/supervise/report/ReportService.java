package com.pmo.supervise.report;

import com.pmo.supervise.common.NotifyType;
import com.pmo.supervise.common.TaskStatus;
import com.pmo.supervise.config.AppProperties;
import com.pmo.supervise.dashboard.DashboardService;
import com.pmo.supervise.notify.NotificationKeys;
import com.pmo.supervise.notify.NotificationService;
import com.pmo.supervise.notify.dto.CreateNotificationCommand;
import com.pmo.supervise.task.TaskSql;
import com.pmo.supervise.user.dto.UserRow;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.List;

/**
 * 日报 / 周报内容与投递（TECHNICAL §4.7，AC-20）。
 *
 * <p>日报正文直接取 {@link DashboardService#buildDailyMarkdown(LocalDate)}：KPI、焦点与看板同一次查询，
 * 保证“日报里的逾期数 = 看板上的逾期数”不是靠人工对齐而是结构上只有一份。
 * 周报在日报正文后追加本周（周一~周日）新增 / 完成 / 逾期区间与未闭环清单，
 * 周区间内的计数用 created_at / completed_at / due_date，逾期沿用 {@link TaskSql#OVERDUE}（与看板同谓词）。</p>
 */
@Service
public class ReportService {

    private static final int UNCLOSED_LIMIT = 10;

    private final DashboardService dashboardService;
    private final NotificationService notificationService;
    private final NamedParameterJdbcTemplate jdbc;
    private final AppProperties appProperties;

    public ReportService(DashboardService dashboardService, NotificationService notificationService,
                         NamedParameterJdbcTemplate jdbc, AppProperties appProperties) {
        this.dashboardService = dashboardService;
        this.notificationService = notificationService;
        this.jdbc = jdbc;
        this.appProperties = appProperties;
    }

    /** 日报正文：与看板完全同源（同一个 load()），不再二次聚合。 */
    public String buildDailyBody(LocalDate date) {
        return dashboardService.buildDailyMarkdown(date);
    }

    /** 周报正文 = 日报正文 + 本周区间统计 + 未闭环清单。 */
    public String buildWeeklyBody(LocalDate date) {
        LocalDate weekStart = date.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
        LocalDate weekEnd = weekStart.plusDays(6);
        long created = countCreated(weekStart, weekEnd);
        long completed = countCompleted(weekStart, weekEnd);
        long dueThisWeekOpen = countDueInWeekUnfinished(weekStart, weekEnd);
        List<UnclosedRow> unclosed = unclosedTasks(date);

        StringBuilder md = new StringBuilder(buildDailyBody(date));
        md.append("\n### 本周督办 · ").append(weekStart).append(" ~ ").append(weekEnd).append('\n');
        md.append("- 本周新增任务：").append(created).append('\n');
        md.append("- 本周完成：").append(completed).append('\n');
        md.append("- 本周到期仍未完成：").append(dueThisWeekOpen).append('\n');
        md.append("\n**未闭环任务（前 ").append(unclosed.size()).append(" 条）**\n\n");
        if (unclosed.isEmpty()) {
            md.append("无未完成任务。\n");
        } else {
            int index = 1;
            for (UnclosedRow row : unclosed) {
                md.append(index++).append(". ").append(row.title())
                        .append("｜").append(row.ownerName())
                        .append("｜截止 ").append(row.dueDate())
                        .append("｜").append(TaskStatus.of(row.status()).label());
                if (row.overdueDays() > 0) {
                    md.append("｜已逾期 ").append(row.overdueDays()).append(" 天");
                }
                md.append('\n');
            }
        }
        return md.toString();
    }

    public String title(NotifyType type, LocalDate date) {
        return type.label() + " · " + date;
    }

    /**
     * 逐人创建站内通知并触发钉钉推送（与扫描同一 dedup + 重试链路）：
     * createIfAbsent 命中唯一键即视为“本次已发过”，因此重复触发不会产生第二批通知。
     *
     * @return 本次新建的通知条数（created 的数量即 pushed，其余计入 skipped）
     */
    public int dispatch(List<UserRow> recipients, NotifyType type, LocalDate date, String title, String body) {
        if (recipients == null || recipients.isEmpty()) {
            return 0;
        }
        String url = appProperties.getFrontendBaseUrl() + "/dashboard";
        int created = 0;
        for (UserRow recipient : recipients) {
            var notification = notificationService.createIfAbsent(CreateNotificationCommand.async(
                    recipient.id(), type, null, null, title, body,
                    NotificationKeys.report(recipient.id(), date, type), url));
            if (notification.isPresent()) {
                created++;
            }
        }
        return created;
    }

    private long countCreated(LocalDate weekStart, LocalDate weekEnd) {
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("start", weekStart.atStartOfDay())
                .addValue("end", weekEnd.plusDays(1).atStartOfDay());
        Long count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM tasks t WHERE t.created_at >= :start AND t.created_at < :end",
                params, Long.class);
        return count == null ? 0 : count;
    }

    private long countCompleted(LocalDate weekStart, LocalDate weekEnd) {
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("start", weekStart.atStartOfDay())
                .addValue("end", weekEnd.plusDays(1).atStartOfDay());
        Long count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM tasks t WHERE t.completed_at >= :start AND t.completed_at < :end",
                params, Long.class);
        return count == null ? 0 : count;
    }

    private long countDueInWeekUnfinished(LocalDate weekStart, LocalDate weekEnd) {
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("startDate", weekStart)
                .addValue("endDate", weekEnd);
        Long count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM tasks t WHERE " + TaskSql.NOT_FINISHED
                        + " AND t.due_date >= :startDate AND t.due_date <= :endDate",
                params, Long.class);
        return count == null ? 0 : count;
    }

    /** 未闭环清单：截止日升序，逾期天数由服务端按同一 OVERDUE 谓词算好，前端不推算。 */
    private List<UnclosedRow> unclosedTasks(LocalDate date) {
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("today", date)
                .addValue("limit", UNCLOSED_LIMIT);
        String sql = """
                SELECT t.id, t.title, u.name AS owner_name, t.due_date, t.status,
                       CASE WHEN %s THEN DATEDIFF(:today, t.due_date) ELSE 0 END AS overdue_days
                FROM tasks t
                JOIN users u ON u.id = t.owner_id
                WHERE %s
                ORDER BY t.due_date ASC, t.id ASC
                LIMIT :limit
                """.formatted(TaskSql.OVERDUE, TaskSql.NOT_FINISHED);
        List<UnclosedRow> rows = new ArrayList<>();
        jdbc.query(sql, params, rs -> {
            rows.add(new UnclosedRow(
                    rs.getLong("id"),
                    rs.getString("title"),
                    rs.getString("owner_name"),
                    rs.getObject("due_date", LocalDate.class),
                    rs.getString("status"),
                    rs.getInt("overdue_days")));
        });
        return rows;
    }

    private record UnclosedRow(long id, String title, String ownerName, LocalDate dueDate,
                               String status, int overdueDays) {
    }
}
