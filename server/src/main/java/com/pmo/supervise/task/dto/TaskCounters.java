package com.pmo.supervise.task.dto;

/**
 * 看板 6 项 KPI 的原始计数（TECHNICAL §5.5）。口径只在 TaskQueryDao 计算一次。
 *
 * <p>r7 末尾追加 {@code risk}（风险 = 逾期 ∪ 待验收满 3 天）：看板仍只取前 6 项，
 * 风险数与甘特摘要同源，保证「看板说有 N 条，甘特上能找到 N 条」。
 */
public record TaskCounters(
        long total,
        long unfinished,
        long overdue,
        long dueSoon24h,
        long pendingAccept,
        long done,
        long risk) {
}
