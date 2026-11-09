package com.pmo.supervise.meeting.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.pmo.supervise.common.TaskStatus;
import com.pmo.supervise.task.dto.UserRef;

import java.time.LocalDate;

/**
 * 决议事项行（前端 DecisionItemRow）。converted / taskId / taskStatus 由
 * tasks.source_decision_item_id 反查；isOverdue / overdueDays 由服务端算好，
 * 前端不推算日期（AC-11）。已转任务后按任务的状态与截止日判定，未转按决议项自身。
 */
public record DecisionItemRow(
        long id,
        String description,
        UserRef owner,
        LocalDate dueDate,
        String deliveryNote,
        int sortNo,
        boolean converted,
        Long taskId,
        TaskStatus taskStatus,
        @JsonProperty("isOverdue") boolean isOverdue,
        int overdueDays) {
}
