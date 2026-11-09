package com.pmo.supervise.meeting.dto;

import java.time.LocalDate;

/**
 * 决议事项请求（前端 DecisionItemRequest）。逐项校验在 MeetingService 内完成：
 * 缺失项以 422 + fieldErrors[index] 回传，index 为该行在当前排序中的位置（0 基）。
 */
public record DecisionItemRequest(
        String description,
        Long ownerId,
        LocalDate dueDate,
        String deliveryNote) {
}
