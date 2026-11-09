package com.pmo.supervise.task.dto;

import com.pmo.supervise.common.Priority;
import com.pmo.supervise.common.SourceType;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;

/**
 * 编辑任务（TECHNICAL §5.4）。source* 字段只用于识别“改来源”的非法请求：
 * 任何来源字段出现即 422 TASK_SOURCE_IMMUTABLE。
 */
public record UpdateTaskRequest(
        @Size(max = 100, message = "任务名称不能超过 100 字")
        String title,

        Long ownerId,

        LocalDate dueDate,

        Priority priority,

        @Size(max = 200, message = "来源说明不能超过 200 字")
        String sourceNote,

        SourceType sourceType,

        Long sourceMeetingId,

        Long sourceDecisionItemId) {

    public boolean hasSourceFields() {
        return sourceType != null || sourceMeetingId != null || sourceDecisionItemId != null;
    }
}
