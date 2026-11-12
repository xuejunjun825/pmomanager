package com.pmo.supervise.task.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.pmo.supervise.common.TaskStatus;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 任务池行（前端 TaskRow）。isOverdue/overdueDays/dueSoon 全部由服务端算好，
 * 前端不做日期推算（AC-11 / AC-17）。
 *
 * <p>r7 追加 theme / plannedStartDate / atRisk：主题三色与风险同样由服务端算好，
 * 任务池、详情、会议任务区、甘特共用同一个行类型，页面不再各自拼主题或算风险。
 */
public record TaskRow(
        long id,
        String title,
        UserRef owner,
        LocalDate dueDate,
        TaskSource source,
        TaskStatus status,
        String statusLabel,
        @JsonProperty("isOverdue") boolean isOverdue,
        int overdueDays,
        boolean dueSoon,
        long urgeCount,
        LocalDateTime lastUrgedAt,
        LocalDateTime updatedAt,
        LocalDateTime completedAt,
        ThemeRef theme,
        LocalDate plannedStartDate,
        boolean atRisk) {
}
