package com.pmo.supervise.gantt.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.pmo.supervise.task.dto.UserRef;

import java.time.LocalDate;
import java.util.List;

/**
 * 甘特聚合响应（TECHNICAL §3.4 / AC-7 / AC-10 / AC-12）：
 * 一棵「主题 → 项目 → 任务」树 + 窗口元信息 + 摘要，一次请求装完整张图。
 *
 * <p>所有日期比较、逾期/风险判定、窗口裁剪与分组都在服务端完成——
 * 前端 useGanttWindow 只做「日期 → 像素」，不再判断任何一条任务是否逾期。
 * {@code pinnedStart/pinnedEnd} 告诉前端这条任务的一端被窗口裁掉了，
 * 因此「逾期条/右界外风险条」仍能在图上被定位，与摘要数字同源。
 */
public record GanttView(
        LocalDate today,
        LocalDate from,
        LocalDate to,
        boolean truncated,
        int windowTotal,
        Summary summary,
        List<ThemeGroup> groups) {

    /**
     * 摘要六数：逾期/风险是全局口径（复用看板 counters），其余是当前窗口口径。
     * 点 chip 即筛选，数字与图上的条必须一一对得上（AC-7）。
     */
    public record Summary(
            long overdue,
            long risk,
            long inProgress,
            long pendingAccept,
            long done,
            long unassigned) {
    }

    /**
     * 主题主行：{@code unassigned=true} 是「未归主题」行（themeId/三色为空串），
     * 恒排在所有主题之后（SQL 已保证，Service 按首次出现顺序建 LinkedHashMap）。
     */
    public record ThemeGroup(
            Long themeId,
            String themeName,
            String color,
            String softColor,
            String borderColor,
            boolean enabled,
            boolean unassigned,
            long taskCount,
            long overdueCount,
            long riskCount,
            List<ProjectGroup> projects) {
    }

    /** 项目子行：{@code unspecified=true} 表示任务未绑定来源项目，恒排在主题内最后。 */
    public record ProjectGroup(
            Long projectId,
            String projectName,
            boolean unspecified,
            long taskCount,
            List<Task> tasks) {
    }

    /**
     * 单条任务条。{@code startDate} 在 plannedStartDate 为空时按创建日推算并标 estimatedStart；
     * 条上不写字，信息在 tooltip（标题/负责人/来源/起止/状态/逾期天数）。
     */
    public record Task(
            long id,
            String title,
            UserRef owner,
            String status,
            String statusLabel,
            LocalDate startDate,
            LocalDate plannedStartDate,
            LocalDate dueDate,
            boolean estimatedStart,
            @JsonProperty("isOverdue") boolean isOverdue,
            int overdueDays,
            @JsonProperty("isAtRisk") boolean isAtRisk,
            boolean pinnedStart,
            boolean pinnedEnd,
            Long projectId,
            String projectName,
            String sourceLabel,
            String sourceColor) {
    }
}
