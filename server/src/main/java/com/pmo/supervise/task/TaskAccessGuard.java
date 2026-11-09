package com.pmo.supervise.task;

import com.pmo.supervise.common.Actor;
import com.pmo.supervise.common.BusinessException;
import org.springframework.stereotype.Component;

/**
 * 唯一权限判定点（TECHNICAL §4.3）：查看 / 操作 / 验收 / 改派 / 管理员 / 催办。
 * owner 越界一律 404，避免用状态码泄露任务存在性。
 */
@Component
public class TaskAccessGuard {

    public void requireView(Actor actor, Task task) {
        if (actor.isAdmin() || actor.isExec()) {
            return;
        }
        if (task.getOwnerId() == actor.id()) {
            return;
        }
        throw BusinessException.notFound("任务不存在");
    }

    public void requireOperate(Actor actor, Task task) {
        if (actor.isAdmin() || task.getOwnerId() == actor.id()) {
            return;
        }
        throw BusinessException.forbidden("只有负责人可以操作该任务");
    }

    public void requireAccept(Actor actor, Task task) {
        if (actor.isAdmin() || task.getDispatcherId() == actor.id()) {
            return;
        }
        throw BusinessException.forbidden("只有派发人可以验收该任务");
    }

    public void requireAdmin(Actor actor) {
        if (actor.isAdmin()) {
            return;
        }
        throw BusinessException.forbidden("需要管理员权限");
    }

    public void requireAssign(Actor actor) {
        if (actor.isAdmin() || actor.isExec()) {
            return;
        }
        throw BusinessException.forbidden("只有管理员或执行领导可以改派/改期");
    }

    public void requireUrge(Actor actor) {
        if (actor.isAdmin() || actor.isExec()) {
            return;
        }
        throw BusinessException.forbidden("只有管理员或执行领导可以催办");
    }
}
