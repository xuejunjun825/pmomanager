package com.pmo.supervise.task.dto;

import com.pmo.supervise.common.Priority;
import com.pmo.supervise.common.SourceType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;

/**
 * 新建任务（TECHNICAL §5.4）。四字段 title/ownerId/dueDate/sourceType 强制必填，
 * 来源组合（会议 or 说明）由 Service 校验，库层 CHECK 兜底。
 */
public record CreateTaskRequest(
        @NotBlank(message = "任务名称不能为空")
        @Size(max = 100, message = "任务名称不能超过 100 字")
        String title,

        @NotNull(message = "负责人不能为空")
        Long ownerId,

        @NotNull(message = "截止时间不能为空")
        LocalDate dueDate,

        @NotNull(message = "来源类型不能为空")
        SourceType sourceType,

        Long sourceMeetingId,

        Long sourceDecisionItemId,

        @Size(max = 200, message = "来源说明不能超过 200 字")
        String sourceNote,

        Priority priority) {
}
