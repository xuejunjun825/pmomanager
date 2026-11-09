package com.pmo.supervise.report.dto;

import java.time.LocalDate;

/**
 * 一次日报/周报的结果摘要（写入 job_runs.detail 并记日志，TECHNICAL §4.7）。
 *
 * <p>{@code recipients} 是名单人数；{@code pushed} 是本次真正新建的通知条数
 * （dedup_key 已存在的记为 skipped——重复触发不会产生第二批通知，AC-20）；
 * {@code status=NO_RECIPIENTS} 表示名单为空、按契约不发送但必须留痕。</p>
 */
public record ReportResult(LocalDate date, int recipients, int pushed, int skipped, String status) {

    public static final String ALREADY_RUN = "ALREADY_RUN";
    public static final String NO_RECIPIENTS = "NO_RECIPIENTS";
    public static final String SUCCESS = "SUCCESS";
    public static final String FAILED = "FAILED";

    public static ReportResult alreadyRun(LocalDate date) {
        return new ReportResult(date, 0, 0, 0, ALREADY_RUN);
    }

    /** 名单为空：不发送、不产生通知，但 job_runs 保留“未配置收件人”的明确痕迹。 */
    public static ReportResult noRecipients(LocalDate date) {
        return new ReportResult(date, 0, 0, 0, NO_RECIPIENTS);
    }

    public static ReportResult delivered(LocalDate date, int recipients, int pushed) {
        return new ReportResult(date, recipients, pushed, Math.max(recipients - pushed, 0), SUCCESS);
    }

    public static ReportResult failed(LocalDate date, int recipients, int pushed) {
        return new ReportResult(date, recipients, pushed, Math.max(recipients - pushed, 0), FAILED);
    }

    public String detail() {
        String base = "recipients=" + recipients + ",pushed=" + pushed + ",skipped=" + skipped;
        if (NO_RECIPIENTS.equals(status)) {
            return base + ",未配置收件人";
        }
        return base;
    }
}
