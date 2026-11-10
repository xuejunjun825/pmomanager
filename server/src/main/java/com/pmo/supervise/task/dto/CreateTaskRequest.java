package com.pmo.supervise.task.dto;

import com.pmo.supervise.common.Priority;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;

/**
 * 新建任务（TECHNICAL §5.4）。四字段 title/ownerId/dueDate/sourceType 强制必填，
 * 来源组合（会议 or 说明）由 Service 按配置的形态校验，库层 CHECK 兜底。
 *
 * <p>sourceType 是 source_types.code 字符串——来源类型已是配置数据，不再是枚举。
 */
public record CreateTaskRequest(
        @NotBlank(message = "任务名称不能为空")
        @Size(max = 100, message = "任务名称不能超过 100 字")
        String title,

        @NotNull(message = "负责人不能为空")
        Long ownerId,

        @NotNull(message = "截止时间不能为空")
        LocalDate dueDate,

        @NotBlank(message = "来源类型不能为空")
        String sourceType,

        Long sourceMeetingId,

        Long sourceDecisionItemId,

        @Size(max = 200, message = "来源说明不能超过 200 字")
        String sourceNote,

        Priority priority) {
}
