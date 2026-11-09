package com.pmo.supervise.task.dto;

import com.pmo.supervise.common.Role;

/** 人员引用（前端 UserRef）：只暴露展示所需字段。 */
public record UserRef(long id, String name, Role role) {

    public UserRef(long id, String name) {
        this(id, name, null);
    }
}
