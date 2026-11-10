package com.pmo.supervise.common;

/** 通知类型（TECHNICAL §3.3）。 */
public enum NotifyType {
    DUE_SOON("即将到期"),
    OVERDUE("逾期提醒"),
    URGE("催办"),
    MENTION("被@"),
    REJECTED("验收打回"),
    REASSIGNED("任务改派"),
    RESCHEDULED("截止改期"),
    DAILY_REPORT("督办日报"),
    WEEKLY_REPORT("督办周报");

    private final String label;

    NotifyType(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }

    public static NotifyType of(String dbValue) {
        return NotifyType.valueOf(dbValue);
    }
}
