package com.pmo.supervise.task.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 任务详情（前端 TaskDetail = TaskRow + 扩展字段，JSON 扁平）。
 * events 与 permissions 由服务端补齐：事件按时间正序，动作权限按当前身份计算。
 */
public record TaskDetailRow(
        long id,
        String title,
        UserRef owner,
        java.time.LocalDate dueDate,
        TaskSource source,
        com.pmo.supervise.common.TaskStatus status,
        String statusLabel,
        @JsonProperty("isOverdue") boolean isOverdue,
        int overdueDays,
        boolean dueSoon,
        long urgeCount,
        LocalDateTime lastUrgedAt,
        LocalDateTime updatedAt,
        LocalDateTime completedAt,
        UserRef dispatcher,
        com.pmo.supervise.common.Priority priority,
        String completionNote,
        String completionUrl,
        LocalDateTime submittedAt,
        List<TaskEventRow> events,
        TaskPermissions permissions) {

    public TaskDetailRow with(List<TaskEventRow> newEvents, TaskPermissions newPermissions) {
        return new TaskDetailRow(id, title, owner, dueDate, source, status, statusLabel, isOverdue,
                overdueDays, dueSoon, urgeCount, lastUrgedAt, updatedAt, completedAt, dispatcher, priority,
                completionNote, completionUrl, submittedAt, newEvents, newPermissions);
    }

    public TaskRow toRow() {
        return new TaskRow(id, title, owner, dueDate, source, status, statusLabel, isOverdue, overdueDays,
                dueSoon, urgeCount, lastUrgedAt, updatedAt, completedAt);
    }
}
