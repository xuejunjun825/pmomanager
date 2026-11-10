package com.pmo.supervise.task.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.Map;

/**
 * 任务详情动作权限（前端 TaskPermissions）。由服务端算好，
 * 前端只按 permissions 渲染按钮，不在客户端复制权限规则。
 *
 * <p>canAddProgress：能否追加进度（管理员/管理层/负责人/被 @ 的人）；
 * mentionOnly：被 @ 的只读视角，状态动作整组不渲染，接口侧仍 403 兜底。
 */
public record TaskPermissions(
        boolean canStart,
        boolean canSubmit,
        boolean canAccept,
        boolean canReject,
        boolean canCancel,
        boolean canRestore,
        boolean canForceComplete,
        boolean canReassign,
        boolean canReschedule,
        boolean canUrge,
        boolean canAddProgress,
        boolean mentionOnly,
        @JsonProperty("disabledReasons") Map<String, String> disabledReasons) {

    public static TaskPermissions none(Map<String, String> disabledReasons) {
        return new TaskPermissions(false, false, false, false, false, false, false, false, false, false,
                false, false, disabledReasons);
    }
}
