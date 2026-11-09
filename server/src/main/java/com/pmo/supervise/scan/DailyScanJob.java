package com.pmo.supervise.scan;

import com.pmo.supervise.common.JobKey;
import com.pmo.supervise.common.NotifyType;
import com.pmo.supervise.common.ShanghaiClock;
import com.pmo.supervise.common.TaskStatus;
import com.pmo.supervise.config.AppProperties;
import com.pmo.supervise.notify.MessageTemplates;
import com.pmo.supervise.notify.NotificationKeys;
import com.pmo.supervise.notify.NotificationService;
import com.pmo.supervise.notify.dto.CreateNotificationCommand;
import com.pmo.supervise.scan.dto.ScanResult;
import com.pmo.supervise.task.TaskSql;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 每日扫描（TECHNICAL §4.7）：09:00 扫描 T-1 到期与逾期，启动时补跑仅补当天；
 * 每条通知走 dedup_key，跑两遍条数不变。
 */
@Component
public class DailyScanJob {

    private static final Logger log = LoggerFactory.getLogger(DailyScanJob.class);

    private final JobRunService jobRunService;
    private final NotificationService notificationService;
    private final ShanghaiClock clock;
    private final NamedParameterJdbcTemplate jdbc;
    private final AppProperties appProperties;

    public DailyScanJob(JobRunService jobRunService, NotificationService notificationService,
                        ShanghaiClock clock, NamedParameterJdbcTemplate jdbc, AppProperties appProperties) {
        this.jobRunService = jobRunService;
        this.notificationService = notificationService;
        this.clock = clock;
        this.jdbc = jdbc;
        this.appProperties = appProperties;
    }

    @Scheduled(cron = "${supervise.scan.cron:0 0 9 * * *}", zone = "Asia/Shanghai")
    public void scheduled() {
        runForDate(clock.today());
    }

    /** 启动补跑：今天 09:00 已过且当日未跑过，才补当天（不做历史全量轰炸）。 */
    @EventListener(ApplicationReadyEvent.class)
    public void startupCatchUp() {
        if (!appProperties.getScan().isStartupCatchupEnabled()) {
            return;
        }
        LocalDate today = clock.today();
        if (clock.now().toLocalTime().isBefore(LocalTime.of(9, 0))) {
            return;
        }
        if (jobRunService.hasRun(JobKey.DAILY_SCAN, today)) {
            return;
        }
        runForDate(today);
    }

    public ScanResult runForDate(LocalDate date) {
        if (!jobRunService.tryStart(JobKey.DAILY_SCAN, date, clock.now())) {
            return ScanResult.alreadyRun(date);
        }
        int dueSoonCount = 0;
        int overdueCount = 0;
        int pushed = 0;
        int skipped = 0;
        try {
            List<TaskBrief> dueSoon = dueTasks(date.plusDays(1));
            dueSoonCount = dueSoon.size();
            for (TaskBrief task : dueSoon) {
                boolean created = notifyOwner(task, NotifyType.DUE_SOON,
                        MessageTemplates.dueSoonTitle(task.title()),
                        MessageTemplates.dueSoonBody(task.title(), task.dueDate()), date);
                if (created) {
                    pushed++;
                } else {
                    skipped++;
                }
            }

            List<TaskBrief> overdue = overdueTasks(date);
            overdueCount = overdue.size();
            for (TaskBrief task : overdue) {
                long days = ChronoUnit.DAYS.between(task.dueDate(), date);
                boolean created = notifyOwner(task, NotifyType.OVERDUE,
                        MessageTemplates.overdueTitle(task.title()),
                        MessageTemplates.overdueBody(task.title(), task.dueDate(), days), date);
                if (created) {
                    pushed++;
                } else {
                    skipped++;
                }
            }
            ScanResult result = new ScanResult(date, dueSoonCount, overdueCount, pushed, skipped, "SUCCESS");
            jobRunService.finish(JobKey.DAILY_SCAN, date, result.detail(), clock.now());
            log.info("每日扫描完成：date={}, dueSoon={}, overdue={}, pushed={}, skipped={}",
                    date, dueSoonCount, overdueCount, pushed, skipped);
            return result;
        } catch (RuntimeException ex) {
            log.error("每日扫描失败：date={}", date, ex);
            jobRunService.finish(JobKey.DAILY_SCAN, date, "error=" + ex.getMessage(), clock.now());
            return ScanResult.failed(date, dueSoonCount, overdueCount, pushed, skipped, "FAILED");
        }
    }

    private boolean notifyOwner(TaskBrief task, NotifyType type, String title, String body, LocalDate date) {
        Optional<?> created = notificationService.createIfAbsent(CreateNotificationCommand.async(
                task.ownerId(), type, task.id(), task.title(), title, body,
                NotificationKeys.daily(task.id(), date, type), "/tasks/" + task.id()));
        return created.isPresent();
    }

    private List<TaskBrief> dueTasks(LocalDate dueDate) {
        return jdbc.query("""
                SELECT t.id, t.title, t.owner_id, t.due_date FROM tasks t
                WHERE """ + " " + TaskSql.DUE_SOON + """
                ORDER BY t.id
                """, Map.of("tomorrow", dueDate), (rs, rowNum) -> new TaskBrief(
                rs.getLong("id"), rs.getString("title"), rs.getLong("owner_id"),
                rs.getObject("due_date", LocalDate.class)));
    }

    private List<TaskBrief> overdueTasks(LocalDate today) {
        return jdbc.query("""
                SELECT t.id, t.title, t.owner_id, t.due_date FROM tasks t
                WHERE """ + " " + TaskSql.OVERDUE + """
                ORDER BY t.id
                """, Map.of("today", today), (rs, rowNum) -> new TaskBrief(
                rs.getLong("id"), rs.getString("title"), rs.getLong("owner_id"),
                rs.getObject("due_date", LocalDate.class)));
    }

    private record TaskBrief(long id, String title, long ownerId, LocalDate dueDate) {
    }
}
