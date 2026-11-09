package com.pmo.supervise.task.dto;

import java.time.LocalDate;

/**
 * 状态流转请求体（按动作取用其中字段）：
 * submit → completionNote/completionUrl；reject/cancel/forceComplete → reason；
 * reassign → ownerId/reason；reschedule → dueDate/reason。
 */
public record TransitionRequest(
        String reason,
        Long ownerId,
        LocalDate dueDate,
        String completionNote,
        String completionUrl) {
}
