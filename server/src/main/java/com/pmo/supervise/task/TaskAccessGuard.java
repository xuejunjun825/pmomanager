package com.pmo.supervise.task;

import com.pmo.supervise.common.Actor;
import com.pmo.supervise.common.BusinessException;
import com.pmo.supervise.common.ErrorCode;
import com.pmo.supervise.task.progress.TaskProgressDao;
import org.springframework.stereotype.Component;

/**
 * 唯一权限判定点（TECHNICAL §4.3）：查看 / 操作 / 验收 / 改派 / 管理员 / 催办 / 写进度。
 * owner 越界一律 404，避免用状态码泄露任务存在性。
 *
 * <p>被 @ 的人获得只读可见（AC-7、AC-9）：详情 200 但状态动作 403；
 * 写进度是独立语义，非可查看者提交进度必须 403 而详情仍 404，所以分成两个方法。
 */
@Component
public class TaskAccessGuard {

    private final TaskProgressDao progressDao;

    public TaskAccessGuard(TaskProgressDao progressDao) {
        this.progressDao = progressDao;
    }

    /** 可查看 = 管理员 / 管理层 / 负责人 / 被 @ 过的人。 */
    public boolean canView(Actor actor, Task task) {
        if (actor.isAdmin() || actor.isExec()) {
            return true;
        }
        if (task.getOwnerId() == actor.id()) {
            return true;
        }
        return progressDao.isMentioned(task.getId(), actor.id());
    }

    public void requireView(Actor actor, Task task) {
        if (canView(actor, task)) {
            return;
        }
        throw BusinessException.notFound("任务不存在");
    }

    /** 追加进度：可查看者都可以写；不可查看者 403（不是 404，AC-8 的字面要求）。 */
    public void requireProgressWrite(Actor actor, Task task) {
        if (canView(actor, task)) {
            return;
        }
        throw new BusinessException(ErrorCode.PROGRESS_FORBIDDEN, "你没有补充进度的权限");
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
