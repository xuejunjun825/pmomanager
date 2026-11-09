package com.pmo.supervise.task.dto;

/** 任务详情事件行（前端 TaskEvent）。 */
public record TaskEventRow(
        long id,
        String action,
        String actionLabel,
        UserRef actor,
        String fromStatus,
        String toStatus,
        String reason,
        java.time.LocalDateTime createdAt) {
}
