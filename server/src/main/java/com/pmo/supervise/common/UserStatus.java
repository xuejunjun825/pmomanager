package com.pmo.supervise.common;

/** 用户状态（TECHNICAL §3.3）。 */
public enum UserStatus {
    ACTIVE("启用"),
    DISABLED("已停用");

    private final String label;

    UserStatus(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }

    public static UserStatus of(String dbValue) {
        return UserStatus.valueOf(dbValue);
    }
}
