package com.pmo.supervise.common;

import java.util.Objects;

/**
 * 已认证操作者（TECHNICAL §4.3）。
 * Service 只认 Actor，不读 SecurityContextHolder，使权限逻辑可单测。
 */
public record Actor(long id, Role role, String name) {

    public Actor {
        Objects.requireNonNull(role, "role");
        Objects.requireNonNull(name, "name");
    }

    public boolean isAdmin() {
        return role == Role.ADMIN;
    }

    public boolean isExec() {
        return role == Role.EXEC;
    }

    /** 管理员或管理层：可读全量、可改派改期、可催办 */
    public boolean isGlobalReader() {
        return role.isGlobalReader();
    }

    public boolean isOwner() {
        return role == Role.OWNER;
    }
}
