package com.pmo.supervise.scan;

import com.pmo.supervise.IntegrationTestSupport;
import com.pmo.supervise.scan.dto.ScanResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 每日扫描（TECHNICAL §4.7）：09:00 触发、启动只补当天、一天只跑一次。
 * 同一 date 跑两遍，通知条数不变——这是 AC-19 去重的核心断言。
 */
class DailyScanJobIT extends IntegrationTestSupport {

    @Autowired
    private DailyScanJob scanJob;

    @Test
    @DisplayName("T-1 预警 + 逾期各生成一条，重复扫描不再新增")
    void scanIsIdempotentPerDay() {
        long owner = seedOwner("scan-owner", "扫描负责人");
        LocalDate today = clock.today();
        seedTask("明天到期", owner, today.plusDays(1), "PENDING", adminId);
        seedTask("已经逾期", owner, today.minusDays(3), "IN_PROGRESS", adminId);
        seedTask("已完成不提醒", owner, today.minusDays(3), "DONE", adminId);

        ScanResult first = scanJob.runForDate(today);
        assertThat(first.dueSoon()).isEqualTo(1);
        assertThat(first.overdue()).isEqualTo(1);
        assertThat(first.skipped()).isZero();
        assertThat(notificationCount()).isEqualTo(2);

        ScanResult second = scanJob.runForDate(today);
        assertThat(second.status()).isEqualTo("ALREADY_RUN");
        assertThat(notificationCount()).isEqualTo(2);

        String detail = jdbc.queryForObject(
                "SELECT detail FROM job_runs WHERE job_key = 'DAILY_SCAN' AND run_date = ?",
                String.class, today);
        assertThat(detail).isEqualTo("dueSoon=1,overdue=1,pushed=2,skipped=0");
    }

    @Test
    @DisplayName("已完成/已取消的任务不进入扫描")
    void finishedTasksAreIgnored() {
        long owner = seedOwner("scan-owner2", "扫描负责人二");
        LocalDate today = clock.today();
        seedTask("已取消", owner, today.minusDays(2), "CANCELLED", adminId);

        ScanResult result = scanJob.runForDate(today);
        assertThat(result.dueSoon()).isZero();
        assertThat(result.overdue()).isZero();
        assertThat(notificationCount()).isZero();
    }
}
