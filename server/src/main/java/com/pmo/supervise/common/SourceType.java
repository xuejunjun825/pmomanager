package com.pmo.supervise.common;

/**
 * 任务来源类型（TECHNICAL §3.3）：会议决议 / 项目任务 / 日常待办。
 * MEETING 必须带会议与决议项；PROJECT/DAILY 必须带来源说明（库层 ck_tasks_source 兜底）。
 */
public enum SourceType {
    MEETING("会议决议"),
    PROJECT("项目任务"),
    DAILY("日常待办");

    private final String label;

    SourceType(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }

    public static SourceType of(String dbValue) {
        if (dbValue == null) {
            throw new IllegalArgumentException("source type is null");
        }
        return SourceType.valueOf(dbValue);
    }
}
