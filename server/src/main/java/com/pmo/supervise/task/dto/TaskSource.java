package com.pmo.supervise.task.dto;

/**
 * 任务来源（前端 TaskSource）。来源 = 类型编码 + 名称 + 三色 + 会议/决议项 或 说明，
 * 两种形态互斥，由库层 ck_tasks_source 兜底。
 *
 * <p>颜色由 source_types 配置下发：新增来源类型不需要前端改样式（AC-5）。
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
        String note) {
}
