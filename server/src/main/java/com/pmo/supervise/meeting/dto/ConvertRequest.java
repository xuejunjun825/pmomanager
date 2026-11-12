package com.pmo.supervise.meeting.dto;

import jakarta.validation.constraints.NotNull;

import java.time.LocalDate;
import java.util.List;

/**
 * 决议项转任务请求（前端 ConvertRequest，TECHNICAL §3.3 / AC-9）。
 *
 * <p>批量语义：themeId 与 plannedStartDate 一次应用于本次被转的全部决议项，
 * 不做逐行覆盖（前端在弹窗里明示「应用于本次全部 N 条」）。
 * 两者都可为空：themeId 为空 → 任务「未归主题」；plannedStartDate 为空 → 不上计划开始。
 */
public record ConvertRequest(
        @NotNull(message = "请选择要转任务的决议事项")
        List<Long> itemIds,

        Long themeId,

        LocalDate plannedStartDate) {
}
