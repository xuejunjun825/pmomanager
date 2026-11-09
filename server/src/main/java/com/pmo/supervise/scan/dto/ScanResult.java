package com.pmo.supervise.scan.dto;

import java.time.LocalDate;

/** 一次扫描的结果摘要（写入 job_runs.detail 并返回日志）。 */
public record ScanResult(LocalDate date, int dueSoon, int overdue, int pushed, int skipped, String status) {

    public static ScanResult alreadyRun(LocalDate date) {
        return new ScanResult(date, 0, 0, 0, 0, "ALREADY_RUN");
    }

    public static ScanResult failed(LocalDate date, int dueSoon, int overdue, int pushed, int skipped, String detail) {
        return new ScanResult(date, dueSoon, overdue, pushed, skipped, detail);
    }

    public String detail() {
        return "dueSoon=" + dueSoon + ",overdue=" + overdue + ",pushed=" + pushed + ",skipped=" + skipped;
    }
}
