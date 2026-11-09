package com.pmo.supervise.common;

/**
 * 角色（TECHNICAL §3.3）。
 * ADMIN 管理员 / OWNER 任务负责人 / EXEC 管理层。
 */
public enum Role {
    ADMIN("管理员"),
    OWNER("任务负责人"),
    EXEC("管理层");

    private final String label;

    Role(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }

    public boolean isAdmin() {
        return this == ADMIN;
    }

    /** 可读全量任务/矩阵/看板的角色 */
    public boolean isGlobalReader() {
        return this == ADMIN || this == EXEC;
    }

    public static Role of(String dbValue) {
        if (dbValue == null) {
            throw new IllegalArgumentException("role is null");
        }
        return Role.valueOf(dbValue);
    }
}
