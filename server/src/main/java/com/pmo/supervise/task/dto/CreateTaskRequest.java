package com.pmo.supervise.task.dto;

import com.pmo.supervise.common.Priority;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;

/**
 * 新建任务（TECHNICAL §5.4）。四字段 title/ownerId/dueDate/sourceType 强制必填，
 * 来源组合（会议 / 说明 / 项目）由 Service 按配置的形态校验，库层 CHECK 兜底。
 *
 * <p>sourceType 是 source_types.code 字符串——来源类型已是配置数据，不再是枚举。
 * sourceProjectId 只在 form=PROJECT 时允许（追加在末尾，不破坏既有 JSON 客户端）。
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

        Priority priority,

        /** form=PROJECT 的来源必填；其它形态传了就 422（由 Service 判定，不在这里做形态推断）。 */
        Long sourceProjectId,

        /** 主题（r7）：可空 = 未归主题；非空时必须是启用中的主题（Service 校验，422 回填 themeId）。 */
        Long themeId,

        /** 计划开始（r7）：可空；非空时必须 ≤ 截止日期（422 回填 plannedStartDate）。 */
        LocalDate plannedStartDate) {

    /** 兼容 r7 之前的调用方（无主题/计划开始）；等价于 themeId=null、plannedStartDate=null。 */
    public CreateTaskRequest(String title, Long ownerId, LocalDate dueDate, String sourceType,
                             Long sourceMeetingId, Long sourceDecisionItemId, String sourceNote,
                             Priority priority, Long sourceProjectId) {
        this(title, ownerId, dueDate, sourceType, sourceMeetingId, sourceDecisionItemId, sourceNote,
                priority, sourceProjectId, null, null);
    }
}
