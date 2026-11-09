package com.pmo.supervise.common;

/** 优先级（TECHNICAL §3.3）。 */
public enum Priority {
    HIGH("高"),
    MEDIUM("中"),
    LOW("低");

    private final String label;

    Priority(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }

    public static Priority of(String dbValue) {
        if (dbValue == null || dbValue.isBlank()) {
            return MEDIUM;
        }
        return Priority.valueOf(dbValue);
    }
}
