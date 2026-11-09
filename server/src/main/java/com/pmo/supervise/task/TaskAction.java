package com.pmo.supervise.task;

import com.pmo.supervise.common.TaskStatus;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 任务状态机（TECHNICAL §4.2）：5 状态 18 条合法迁移。
 * 合法性只在这里定义一次；其余 10 组「状态 × 动作」由动作对状态的集合判定拒绝（409）。
 */
public enum TaskAction {

    START("开始处理", Set.of(TaskStatus.PENDING), TaskStatus.IN_PROGRESS),
    SUBMIT("提交验收", Set.of(TaskStatus.PENDING, TaskStatus.IN_PROGRESS), TaskStatus.PENDING_ACCEPT),
    ACCEPT("验收通过", Set.of(TaskStatus.PENDING_ACCEPT), TaskStatus.DONE),
    REJECT("验收打回", Set.of(TaskStatus.PENDING_ACCEPT), TaskStatus.IN_PROGRESS),
    CANCEL("取消任务", Set.of(TaskStatus.PENDING, TaskStatus.IN_PROGRESS, TaskStatus.PENDING_ACCEPT),
            TaskStatus.CANCELLED),
    RESTORE("恢复任务", Set.of(TaskStatus.CANCELLED), TaskStatus.PENDING),
    FORCE_COMPLETE("强制完成", Set.of(TaskStatus.PENDING, TaskStatus.IN_PROGRESS, TaskStatus.PENDING_ACCEPT),
            TaskStatus.DONE),
    REASSIGN("改派", Set.of(TaskStatus.PENDING, TaskStatus.IN_PROGRESS, TaskStatus.PENDING_ACCEPT), null),
    RESCHEDULE("改期", Set.of(TaskStatus.PENDING, TaskStatus.IN_PROGRESS, TaskStatus.PENDING_ACCEPT), null),
    UPDATE("编辑任务", Set.of(TaskStatus.PENDING, TaskStatus.IN_PROGRESS, TaskStatus.PENDING_ACCEPT,
            TaskStatus.DONE, TaskStatus.CANCELLED), null);

    private final String label;
    private final Set<TaskStatus> allowedFrom;
    private final TaskStatus target;

    TaskAction(String label, Set<TaskStatus> allowedFrom, TaskStatus target) {
        this.label = label;
        this.allowedFrom = new LinkedHashSet<>(allowedFrom);
        this.target = target;
    }

    public String label() {
        return label;
    }

    public boolean allows(TaskStatus status) {
        return status != null && allowedFrom.contains(status);
    }

    public TaskStatus target(TaskStatus current) {
        return target == null ? current : target;
    }

    public List<TaskStatus> allowedFrom() {
        return List.copyOf(allowedFrom);
    }

    /** 18 条合法 (动作, 起始状态) 边，供单测与文档核对。 */
    public static Set<String> legalEdges() {
        Set<String> edges = new LinkedHashSet<>();
        for (TaskAction action : values()) {
            if (action == UPDATE) {
                continue; // 编辑不是状态迁移，不计入 18 条
            }
            for (TaskStatus status : action.allowedFrom) {
                edges.add(action.name() + ":" + status.name());
            }
        }
        return edges;
    }

    public static TaskAction of(String value) {
        return TaskAction.valueOf(value);
    }
}
