package com.pmo.supervise.task.dto;

import com.pmo.supervise.common.Priority;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;

/**
 * 编辑任务（TECHNICAL §5.4）。source* 字段只用于识别“改来源”的非法请求：
 * 任何来源字段出现即 422 TASK_SOURCE_IMMUTABLE。
 *
 * <p>sourceType 是 source_types.code 字符串（来源类型为配置数据）；这里只判非空，
 * 是否合法由 Service 决定——改来源本身已经被禁止。
 */
public record UpdateTaskRequest(
        @Size(max = 100, message = "任务名称不能超过 100 字")
        String title,

        Long ownerId,

        LocalDate dueDate,

        Priority priority,

        @Size(max = 200, message = "来源说明不能超过 200 字")
        String sourceNote,

        String sourceType,

        Long sourceMeetingId,

        Long sourceDecisionItemId,

        /** 改来源同样非法：出现即 TASK_SOURCE_IMMUTABLE（含把项目加/换到已建任务上）。 */
        Long sourceProjectId,

        /** 主题（r7）：{@code null} = 不改，{@code 0} = 清空（未归主题），其它 = 改为该主题。 */
        Long themeId,

        /** 计划开始（r7）：{@code null} = 不改；非空时必须 ≤ 当前截止日期。 */
        LocalDate plannedStartDate,

        /** 清空计划开始：日期控件没有 0 语义，用显式布尔避免空字符串解析歧义。 */
        boolean clearPlannedStart) {

    /** 兼容 r7 之前的调用方：主题不改、计划开始不改。 */
    public UpdateTaskRequest(String title, Long ownerId, LocalDate dueDate, Priority priority,
                             String sourceNote, String sourceType, Long sourceMeetingId,
                             Long sourceDecisionItemId, Long sourceProjectId) {
        this(title, ownerId, dueDate, priority, sourceNote, sourceType, sourceMeetingId,
                sourceDecisionItemId, sourceProjectId, null, null, false);
    }

    /** 来源字段判定不包含 r7 的主题/计划开始：那两个是「可编辑字段」，不是来源。 */
    public boolean hasSourceFields() {
        return sourceType != null || sourceMeetingId != null || sourceDecisionItemId != null
                || sourceProjectId != null;
    }
}
