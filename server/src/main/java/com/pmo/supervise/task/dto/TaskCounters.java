package com.pmo.supervise.task.dto;

/** 看板 6 项 KPI 的原始计数（TECHNICAL §5.5）。口径只在 TaskQueryDao 计算一次。 */
public record TaskCounters(
        long total,
        long unfinished,
        long overdue,
        long dueSoon24h,
        long pendingAccept,
        long done) {
}
