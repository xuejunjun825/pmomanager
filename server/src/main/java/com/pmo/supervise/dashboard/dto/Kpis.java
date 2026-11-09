package com.pmo.supervise.dashboard.dto;

/**
 * 看板 6 项 KPI（TECHNICAL §5.5，口径与任务池完全一致）。
 * total = 非取消任务数；unfinished = 四个互斥桶之和（逾期不重复计）；
 * overdueRate = overdue / unfinished，unfinished = 0 时为 0；
 * dueSoon24h = 未完成且 due_date ∈ {today, today+1}（日期粒度下的 24 小时定义）。
 */
public record Kpis(
        long total,
        long unfinished,
        long overdue,
        double overdueRate,
        long dueSoon24h,
        long pendingAccept) {
}
