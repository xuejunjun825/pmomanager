package com.pmo.supervise.gantt.dto;

import java.time.LocalDate;

/**
 * 甘特查询参数（TECHNICAL §3.4 / AC-10 / AC-12）。
 *
 * <p>与 {@link com.pmo.supervise.task.dto.TaskFilter} 分开：甘特没有分页，
 * 窗口是闭区间 {@code [from, to]}，语义与任务池的筛选不是一套。
 * {@code includeDone} 默认 true（缺省显示已完成），{@code onlyRisk} 默认 false；
 * {@code theme} 的空值/{@code none}/{@code <id>} 三态由 ThemeFilterSql 解释。
 */
public record GanttQuery(
        LocalDate from,
        LocalDate to,
        Long ownerId,
        boolean includeDone,
        boolean onlyRisk,
        String theme) {
}
