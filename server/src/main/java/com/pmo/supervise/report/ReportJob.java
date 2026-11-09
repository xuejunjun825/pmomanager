package com.pmo.supervise.report;

import com.pmo.supervise.common.JobKey;
import com.pmo.supervise.common.NotifyType;
import com.pmo.supervise.common.ShanghaiClock;
import com.pmo.supervise.notify.DingTalkSender;
import com.pmo.supervise.report.dto.ReportResult;
import com.pmo.supervise.scan.JobRunService;
import com.pmo.supervise.settings.SettingService;
import com.pmo.supervise.settings.dto.ReportSettings;
import com.pmo.supervise.user.dto.UserRow;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 日报 / 周报定时任务（TECHNICAL §4.7，AC-20）。
 *
 * <p>每分钟轮询而不是固定 cron：发送时间要能在设置页随时改，固定 cron 做不到。
 * 到点判定 = 已启用 && 今天是指定工作日 && now(HH:mm) &gt;= 配置时间；
 * 一天一次由 {@link JobRunService#tryStart} 的 (job_key, run_date) 主键保证，
 * 因此轮询重复触发、进程重启后补跑都只会产生一批通知。</p>
 *
 * <p>日报按 PRD 只在工作日（周一~周五）发送，周报只在周一发送；随后的轮询命中 tryStart 直接短路。</p>
 */
@Component
public class ReportJob {

    private static final Logger log = LoggerFactory.getLogger(ReportJob.class);

    private final SettingService settingService;
    private final ReportService reportService;
    private final JobRunService jobRunService;
    private final DingTalkSender dingTalkSender;
    private final ShanghaiClock clock;

    public ReportJob(SettingService settingService, ReportService reportService,
                     JobRunService jobRunService, DingTalkSender dingTalkSender, ShanghaiClock clock) {
        this.settingService = settingService;
        this.reportService = reportService;
        this.jobRunService = jobRunService;
        this.dingTalkSender = dingTalkSender;
        this.clock = clock;
    }

    @Scheduled(cron = "0 * * * * *", zone = "Asia/Shanghai")
    public void tick() {
        try {
            tickAt(clock.now());
        } catch (RuntimeException ex) {
            log.error("报表轮询失败", ex);
        }
    }

    /** 可注入时间的轮询体（单测用固定时间直接驱动，不必等真实时钟）。 */
    public List<ReportResult> tickAt(LocalDateTime now) {
        LocalDate date = now.toLocalDate();
        LocalTime time = now.toLocalTime();
        ReportSettings settings = settingService.report();
        List<ReportResult> results = new ArrayList<>(2);

        if (settings.dailyEnabled() && isWorkday(date)
                && !time.isBefore(LocalTime.parse(settings.dailyTime()))) {
            results.add(runDaily(date));
        }
        if (settings.weeklyEnabled() && date.getDayOfWeek() == DayOfWeek.MONDAY
                && !time.isBefore(LocalTime.parse(settings.weeklyTime()))) {
            results.add(runWeekly(date));
        }
        return results;
    }

    public ReportResult runDaily(LocalDate date) {
        return run(JobKey.DAILY_REPORT, NotifyType.DAILY_REPORT, date, reportService::buildDailyBody);
    }

    public ReportResult runWeekly(LocalDate date) {
        return run(JobKey.WEEKLY_REPORT, NotifyType.WEEKLY_REPORT, date, reportService::buildWeeklyBody);
    }

    private ReportResult run(JobKey jobKey, NotifyType type, LocalDate date,
                             java.util.function.Function<LocalDate, String> bodyBuilder) {
        if (!jobRunService.tryStart(jobKey, date, clock.now())) {
            return ReportResult.alreadyRun(date);
        }
        List<UserRow> recipients = settingService.report().recipients();
        if (recipients.isEmpty()) {
            String detail = ReportResult.noRecipients(date).detail();
            jobRunService.finish(jobKey, date, detail, clock.now());
            log.warn("{}未发送（未配置收件人）：date={}, job={}", type.label(), date, jobKey);
            return ReportResult.noRecipients(date);
        }
        int pushed = 0;
        try {
            String body = bodyBuilder.apply(date);
            pushed = reportService.dispatch(recipients, type, date, reportService.title(type, date), body);
            ReportResult result = ReportResult.delivered(date, recipients.size(), pushed);
            String detail = result.detail();
            if (!dingTalkSender.isConfigured()) {
                // AC-21：钉钉未配置时通知只落站内，job_runs 必须留下降级事实而不是一句“已发送”。
                detail = detail + ",钉钉未配置";
            }
            jobRunService.finish(jobKey, date, detail, clock.now());
            log.info("{}已处理：date={}, {}", type.label(), date, detail);
            return result;
        } catch (RuntimeException ex) {
            log.error("{}发送失败：date={}", type.label(), date, ex);
            jobRunService.finish(jobKey, date, "error=" + ex.getMessage(), clock.now());
            return ReportResult.failed(date, recipients.size(), pushed);
        }
    }

    /** 工作日 = 周一至周五（PRD AC-20「工作日 18:00 日报」）。 */
    static boolean isWorkday(LocalDate date) {
        DayOfWeek day = date.getDayOfWeek();
        return day != DayOfWeek.SATURDAY && day != DayOfWeek.SUNDAY;
    }
}
