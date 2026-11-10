package com.pmo.supervise.task.dto;

import com.pmo.supervise.source.SourceForm;

/**
 * 任务来源（前端 TaskSource）。来源 = 类型编码 + 名称 + 三色 + 形态对应的引用，
 * 三种形态互斥（会议+决议项 / 自由说明 / 项目），由库层 ck_tasks_source 兜底。
 *
 * <p>颜色由 source_types 配置下发：新增来源类型不需要前端改样式（AC-5）。
 * {@code form} 是来源配置的形态，不是编码——编码 PROJECT 与形态 PROJECT 同名不同义，
 * 前端一切「是否渲染项目位」都读 {@code form}，绝不按 type 猜（TECHNICAL 风险 2）。
 *
 * <p>项目三字段可空：{@code form=PROJECT} 且 {@code projectId == null} 是「改形历史」
 * （来源先有说明型任务、后改成项目型，历史任务不迁移）；{@code projectEnabled == null}
 * 表示未知（未 join 到项目），false 才是已停用。
 */
public record TaskSource(
        String type,
        String typeLabel,
        String color,
        String softColor,
        String borderColor,
        Long meetingId,
        String meetingTitle,
        Long decisionItemId,
        String note,
        SourceForm form,
        Long projectId,
        String projectName,
        Boolean projectEnabled) {
}
