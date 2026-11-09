package com.pmo.supervise.report;

import com.pmo.supervise.IntegrationTestSupport;
import com.pmo.supervise.dashboard.DashboardService;
import com.pmo.supervise.report.dto.ReportResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 日报 / 周报端到端（AC-20）：真实 job_runs、真实通知行、真实看板查询，固定时间驱动 tickAt。
 * 断言三件事——到点发一次、名单为空不发但留痕、日报正文与看板同源。
 */
class ReportJobIT extends IntegrationTestSupport {

    private static final LocalDate MONDAY = LocalDate.of(2026, 11, 9);
    private static final LocalDateTime DAILY_AT = MONDAY.atTime(18, 30);
    private static final LocalDateTime WEEKLY_AT = MONDAY.atTime(9, 30);

    @Autowired
    private ReportJob reportJob;

    @Autowired
    private ReportService reportService;

    @Autowired
    private DashboardService dashboardService;

    @BeforeEach
    void resetSettings() {
        jdbc.update("UPDATE settings SET v = 'true' WHERE k = 'report.daily.enabled'");
        jdbc.update("UPDATE settings SET v = '18:00' WHERE k = 'report.daily.time'");
        jdbc.update("UPDATE settings SET v = 'true' WHERE k = 'report.weekly.enabled'");
        jdbc.update("UPDATE settings SET v = '09:00' WHERE k = 'report.weekly.time'");
        jdbc.update("UPDATE settings SET v = '[]' WHERE k = 'report.recipients'");
    }

    private void recipients(long... ids) {
        StringBuilder json = new StringBuilder("[");
        for (int i = 0; i < ids.length; i++) {
            json.append(i == 0 ? "" : ",").append(ids[i]);
        }
        json.append(']');
        jdbc.update("UPDATE settings SET v = ? WHERE k = 'report.recipients'", json.toString());
    }

    private String jobDetail(String jobKey, LocalDate date) {
        return jdbc.queryForObject(
                "SELECT detail FROM job_runs WHERE job_key = ? AND run_date = ?",
                String.class, jobKey, date);
    }

    private String jobStatus(String jobKey, LocalDate date) {
        return jdbc.queryForObject(
                "SELECT status FROM job_runs WHERE job_key = ? AND run_date = ?",
                String.class, jobKey, date);
    }

    @Test
    void dailyReportRunsOncePerDayAndBodyIsTheDashboardQuery() {
        long owner = seedOwner("reportowner", "报表负责人");
        seedTask("逾期任务", owner, MONDAY.minusDays(2), "PENDING", adminId);
        seedTask("已完成任务", owner, MONDAY.minusDays(5), "DONE", adminId);
        recipients(owner);
        jdbc.update("UPDATE settings SET v = 'false' WHERE k = 'report.weekly.enabled'");

        List<ReportResult> first = reportJob.tickAt(DAILY_AT);
        assertThat(first).hasSize(1);
        assertThat(first.get(0).status()).isEqualTo(ReportResult.SUCCESS);
        assertThat(first.get(0).recipients()).isEqualTo(1);
        assertThat(first.get(0).pushed()).isEqualTo(1);
        assertThat(notificationCount()).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT type FROM notifications", String.class)).isEqualTo("DAILY_REPORT");
        assertThat(jdbc.queryForObject("SELECT dedup_key FROM notifications", String.class))
                .isEqualTo("report:" + owner + ":" + MONDAY + ":DAILY_REPORT");
        assertThat(jobStatus("DAILY_REPORT", MONDAY)).isEqualTo("SUCCESS");
        assertThat(jobDetail("DAILY_REPORT", MONDAY)).contains("recipients=1").contains("pushed=1")
                .contains("钉钉未配置");
        // AC-21：钉钉未配置时通知行明确记为「钉钉未配置」，不进入重试队列
        assertThat(jdbc.queryForObject("SELECT dingtalk_status FROM notifications", String.class))
                .isEqualTo("NOT_CONFIGURED");

        // 正文与看板同一次查询：同一时刻两者逐字相同
        assertThat(reportService.buildDailyBody(MONDAY)).isEqualTo(dashboardService.buildDailyMarkdown(MONDAY));
        assertThat(jdbc.queryForObject("SELECT body FROM notifications", String.class))
                .contains("- 逾期：1")
                .contains("待验收");

        // 第二次轮询：job_runs 已存在，不再产生通知（AC-20 重复触发不重复发送）
        List<ReportResult> second = reportJob.tickAt(DAILY_AT.plusMinutes(1));
        assertThat(second).hasSize(1);
        assertThat(second.get(0).status()).isEqualTo(ReportResult.ALREADY_RUN);
        assertThat(notificationCount()).isEqualTo(1);
    }

    @Test
    void emptyRecipientListSendsNothingButLeavesATrace() {
        long owner = seedOwner("reportowner2", "报表负责人二");
        seedTask("逾期任务", owner, MONDAY.minusDays(1), "PENDING", adminId);
        recipients();
        jdbc.update("UPDATE settings SET v = 'false' WHERE k = 'report.weekly.enabled'");

        List<ReportResult> results = reportJob.tickAt(DAILY_AT);

        assertThat(results).hasSize(1);
        assertThat(results.get(0).status()).isEqualTo(ReportResult.NO_RECIPIENTS);
        assertThat(notificationCount()).isZero();
        assertThat(jobStatus("DAILY_REPORT", MONDAY)).isEqualTo("SUCCESS");
        assertThat(jobDetail("DAILY_REPORT", MONDAY)).contains("未配置收件人");
    }

    @Test
    void weeklyReportAddsWeekTrendAndUnclosedList() {
        long owner = seedOwner("reportowner3", "报表负责人三");
        long newTask = seedTask("本周新增任务", owner, MONDAY.plusDays(3), "IN_PROGRESS", adminId);
        jdbc.update("UPDATE tasks SET created_at = ? WHERE id = ?", MONDAY.atTime(9, 0), newTask);
        long doneTask = seedTask("本周完成任务", owner, MONDAY.minusDays(3), "DONE", adminId);
        jdbc.update("UPDATE tasks SET created_at = ?, completed_at = ? WHERE id = ?",
                MONDAY.minusDays(20).atTime(9, 0), MONDAY.plusDays(1).atTime(15, 0), doneTask);
        seedTask("逾期任务", owner, MONDAY.minusDays(2), "PENDING", adminId);
        jdbc.update("UPDATE tasks SET created_at = ? WHERE title = '逾期任务'", MONDAY.minusDays(30).atTime(9, 0));

        recipients(owner);
        jdbc.update("UPDATE settings SET v = 'false' WHERE k = 'report.daily.enabled'");

        List<ReportResult> results = reportJob.tickAt(WEEKLY_AT);

        assertThat(results).hasSize(1);
        assertThat(results.get(0).status()).isEqualTo(ReportResult.SUCCESS);
        assertThat(jdbc.queryForObject("SELECT type FROM notifications", String.class)).isEqualTo("WEEKLY_REPORT");
        String body = jdbc.queryForObject("SELECT body FROM notifications", String.class);
        assertThat(body)
                .contains("### 本周督办 · 2026-11-09 ~ 2026-11-15")
                .contains("本周新增任务：1")
                .contains("本周完成：1")
                .contains("**未闭环任务（前 2 条）**");
        assertThat(jobStatus("WEEKLY_REPORT", MONDAY)).isEqualTo("SUCCESS");
    }

    @Test
    void tickIsNoopBeforeConfiguredTimeAndOnWeekends() {
        long owner = seedOwner("reportowner4", "报表负责人四");
        recipients(owner);
        jdbc.update("UPDATE settings SET v = 'false' WHERE k = 'report.weekly.enabled'");

        assertThat(reportJob.tickAt(MONDAY.atTime(17, 30))).isEmpty();
        assertThat(reportJob.tickAt(MONDAY.plusDays(5).atTime(18, 30))).isEmpty();
        assertThat(notificationCount()).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM job_runs", Integer.class)).isZero();
    }
}
