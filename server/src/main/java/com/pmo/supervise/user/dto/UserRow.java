package com.pmo.supervise.user.dto;

import com.pmo.supervise.common.Role;
import com.pmo.supervise.common.UserStatus;
import com.pmo.supervise.user.User;

import java.time.LocalDateTime;

/**
 * 用户管理行（AC-2 / TECHNICAL §5.2），字段与 web/src/types/api.ts 的 UserRow 一一对应。
 *
 * <p>dingtalkBound 是"是否绑定钉钉"，前端据此显示绑定态；密码从不出参。</p>
 */
public record UserRow(
        long id,
        String username,
        String name,
        Role role,
        String roleLabel,
        UserStatus status,
        String statusLabel,
        boolean dingtalkBound,
        boolean mustChangePassword,
        LocalDateTime createdAt,
        LocalDateTime lastLoginAt) {

    public static UserRow from(User user) {
        return new UserRow(
                user.getId(),
                user.getUsername(),
                user.getName(),
                user.getRole(),
                user.getRole() == null ? null : user.getRole().label(),
                user.getStatus(),
                user.getStatus() == null ? null : user.getStatus().label(),
                user.getDingtalkUnionId() != null && !user.getDingtalkUnionId().isBlank(),
                user.isMustChangePassword(),
                user.getCreatedAt(),
                user.getLastLoginAt());
    }
}
