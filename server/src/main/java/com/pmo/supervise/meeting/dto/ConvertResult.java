package com.pmo.supervise.meeting.dto;

import java.util.List;

/**
 * 转任务结果（前端 ConvertResult）：部分成功是正常结果，
 * 重复点击命中 uk_tasks_decision 的项进 skipped 而不是整批失败（AC-4）。
 */
public record ConvertResult(List<Created> created, List<Skipped> skipped) {

    public record Created(long itemId, long taskId) {
    }

    public record Skipped(long itemId, String reason, Long taskId) {
        public static final String ALREADY_CONVERTED = "ALREADY_CONVERTED";
    }
}
