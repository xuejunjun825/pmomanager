package com.pmo.supervise.auth.dto;

import com.pmo.supervise.auth.SessionUser;
import com.pmo.supervise.common.Role;
import com.pmo.supervise.user.User;

/**
 * 当前登录用户（TECHNICAL §5.1）。landingPath 由服务端给出，前端不再自行按角色推断。
 */
public record AuthUser(
        long id,
        String name,
        Role role,
        String avatarUrl,
        boolean mustChangePassword,
        String landingPath) {

    public static AuthUser from(User user) {
        return new AuthUser(user.getId(), user.getName(), user.getRole(), user.getAvatarUrl(),
                user.isMustChangePassword(), SessionUser.landingPath(user.getRole()));
    }
}
