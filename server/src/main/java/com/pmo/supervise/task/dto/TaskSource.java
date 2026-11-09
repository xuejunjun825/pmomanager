package com.pmo.supervise.task.dto;

import com.pmo.supervise.common.SourceType;

/**
 * 任务来源（前端 TaskSource）。来源 = 类型 + 会议/决议项 或 类型 + 说明，
 * 两种形态互斥，由库层 ck_tasks_source 兜底。
 */
public record TaskSource(
        SourceType type,
        String typeLabel,
        Long meetingId,
        String meetingTitle,
        Long decisionItemId,
        String note) {
}
