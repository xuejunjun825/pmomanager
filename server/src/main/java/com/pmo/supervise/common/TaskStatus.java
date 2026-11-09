package com.pmo.supervise.common;

/**
 * 任务状态（TECHNICAL §3.3 / §4.2）。
 * 库值、展示词、状态机判定只在这里定义一次，任何页面不得自行拼装。
 */
public enum TaskStatus {
    PENDING("待开始"),
    IN_PROGRESS("进行中"),
    PENDING_ACCEPT("待验收"),
    DONE("已完成"),
    CANCELLED("已取消");

    private final String label;

    TaskStatus(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }

    public boolean isFinished() {
        return this == DONE || this == CANCELLED;
    }

    public boolean isUnfinished() {
        return !isFinished();
    }

    public static TaskStatus of(String dbValue) {
        if (dbValue == null) {
            throw new IllegalArgumentException("task status is null");
        }
        return TaskStatus.valueOf(dbValue);
    }
}
