package com.pmo.supervise.dashboard.dto;

import com.pmo.supervise.task.dto.TaskRow;

import java.time.LocalDate;
import java.util.List;

/**
 * 管理层看板（GET /api/dashboard，AC-16）。
 * focus 只含未完成任务，按「逾期 → 待开始 → 待验收 → 进行中」四段排序；
 * focusTotal 为同一筛选条件下的未完成总数（用于「展开全部」）。
 */
public record DashboardView(
        LocalDate today,
        Kpis kpis,
        List<TaskRow> focus,
        long focusTotal) {
}
