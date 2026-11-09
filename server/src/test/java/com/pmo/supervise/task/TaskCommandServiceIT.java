package com.pmo.supervise.task;

import com.pmo.supervise.IntegrationTestSupport;
import com.pmo.supervise.common.BusinessException;
import com.pmo.supervise.common.ErrorCode;
import com.pmo.supervise.common.TaskStatus;
import com.pmo.supervise.task.dto.TransitionRequest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 状态机落地检查（TECHNICAL §4.2）：18 条合法迁移全部成功且各留 1 条事件；
 * 非法迁移 409；DONE 无出边。
 */
class TaskCommandServiceIT extends IntegrationTestSupport {

    @Autowired
    private TaskCommandService commandService;

    private long ownerId;
    private long otherOwnerId;

    private void seedActors() {
        ownerId = seedOwner("cmd-owner", "负责人");
        otherOwnerId = seedOwner("cmd-owner2", "新负责人");
    }

    @Test
    @DisplayName("18 条合法迁移逐条执行成功，状态与事件正确")
    void allLegalTransitionsSucceed() {
        seedActors();
        LocalDate due = LocalDate.of(2026, 11, 20);

        record Edge(TaskAction action, TaskStatus from) {
        }
        Edge[] edges = {
                new Edge(TaskAction.START, TaskStatus.PENDING),
                new Edge(TaskAction.SUBMIT, TaskStatus.PENDING),
                new Edge(TaskAction.SUBMIT, TaskStatus.IN_PROGRESS),
                new Edge(TaskAction.ACCEPT, TaskStatus.PENDING_ACCEPT),
                new Edge(TaskAction.REJECT, TaskStatus.PENDING_ACCEPT),
                new Edge(TaskAction.CANCEL, TaskStatus.PENDING),
                new Edge(TaskAction.CANCEL, TaskStatus.IN_PROGRESS),
                new Edge(TaskAction.CANCEL, TaskStatus.PENDING_ACCEPT),
                new Edge(TaskAction.RESTORE, TaskStatus.CANCELLED),
                new Edge(TaskAction.FORCE_COMPLETE, TaskStatus.PENDING),
                new Edge(TaskAction.FORCE_COMPLETE, TaskStatus.IN_PROGRESS),
                new Edge(TaskAction.FORCE_COMPLETE, TaskStatus.PENDING_ACCEPT),
                new Edge(TaskAction.REASSIGN, TaskStatus.PENDING),
                new Edge(TaskAction.REASSIGN, TaskStatus.IN_PROGRESS),
                new Edge(TaskAction.REASSIGN, TaskStatus.PENDING_ACCEPT),
                new Edge(TaskAction.RESCHEDULE, TaskStatus.PENDING),
                new Edge(TaskAction.RESCHEDULE, TaskStatus.IN_PROGRESS),
                new Edge(TaskAction.RESCHEDULE, TaskStatus.PENDING_ACCEPT)};

        assertThat(edges).hasSize(18);
        for (Edge edge : edges) {
            long taskId = seedTask("迁移 " + edge.action() + " " + edge.from(), ownerId, due,
                    edge.from().name(), adminId);
            var admin = actor(adminId);
            var owner = actor(ownerId);

            TaskStatus expected = switch (edge.action()) {
                case START -> TaskStatus.IN_PROGRESS;
                case SUBMIT -> TaskStatus.PENDING_ACCEPT;
                case ACCEPT, FORCE_COMPLETE -> TaskStatus.DONE;
                case REJECT -> TaskStatus.IN_PROGRESS;
                case CANCEL -> TaskStatus.CANCELLED;
                case RESTORE -> TaskStatus.PENDING;
                default -> edge.from();
            };

            switch (edge.action()) {
                case START -> commandService.start(taskId, owner);
                case SUBMIT -> commandService.submit(taskId, owner,
                        new TransitionRequest(null, null, null, "已完成全部材料并上传附件", null));
                case ACCEPT -> commandService.accept(taskId, admin);
                case REJECT -> commandService.reject(taskId, admin, "材料不完整，请补充数据");
                case CANCEL -> commandService.cancel(taskId, admin, "需求取消");
                case RESTORE -> commandService.restore(taskId, admin);
                case FORCE_COMPLETE -> commandService.forceComplete(taskId, admin, "线下已确认完成");
                case REASSIGN -> commandService.reassign(taskId, admin, otherOwnerId, "人员调整");
                case RESCHEDULE -> commandService.reschedule(taskId, admin, due.plusDays(7), "客户延期");
                default -> throw new IllegalStateException("unexpected");
            }

            assertThat(taskStatus(taskId)).as(edge + " target").isEqualTo(expected.name());
            assertThat(eventCount(taskId)).as(edge + " events").isEqualTo(1);
        }
    }

    @Test
    @DisplayName("非法迁移 409 且不落事件、不改状态")
    void illegalTransitionIsRejected() {
        seedActors();
        long taskId = seedTask("非法迁移", ownerId, LocalDate.of(2026, 11, 20), "PENDING", adminId);

        assertThatThrownBy(() -> commandService.accept(taskId, actor(adminId)))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).code())
                        .isEqualTo(ErrorCode.TASK_INVALID_TRANSITION));

        assertThat(taskStatus(taskId)).isEqualTo("PENDING");
        assertThat(eventCount(taskId)).isZero();
    }

    @Test
    @DisplayName("DONE 无出边：任何动作都 409")
    void doneHasNoOutgoingTransition() {
        seedActors();
        long taskId = seedTask("已完成任务", ownerId, LocalDate.of(2026, 11, 20), "DONE", adminId);

        assertThatThrownBy(() -> commandService.start(taskId, actor(ownerId)))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> commandService.cancel(taskId, actor(adminId), "无效操作"))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> commandService.reassign(taskId, actor(adminId), otherOwnerId, "无效改派"))
                .isInstanceOf(BusinessException.class);
        assertThat(eventCount(taskId)).isZero();
    }

    @Test
    @DisplayName("提交验收要求完成说明 10 字以上（422）")
    void submitRequiresCompletionNote() {
        seedActors();
        long taskId = seedTask("提交验收", ownerId, LocalDate.of(2026, 11, 20), "IN_PROGRESS", adminId);

        assertThatThrownBy(() -> commandService.submit(taskId, actor(ownerId),
                new TransitionRequest(null, null, null, "太短", null)))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).code())
                        .isEqualTo(ErrorCode.VALIDATION_FAILED));
    }

    @Test
    @DisplayName("改派记录旧负责人 → 新负责人并通知新负责人")
    void reassignRecordsOldAndNewOwner() {
        seedActors();
        long taskId = seedTask("改派留痕", ownerId, LocalDate.of(2026, 11, 20), "PENDING", adminId);

        commandService.reassign(taskId, actor(adminId), otherOwnerId, "原负责人休假");

        String reason = jdbc.queryForObject(
                "SELECT reason FROM task_events WHERE task_id = ? AND action = 'REASSIGN'", String.class, taskId);
        assertThat(reason).contains("负责人").contains("→").contains("原负责人休假");
        assertThat(jdbc.queryForObject("SELECT owner_id FROM tasks WHERE id = ?", Long.class, taskId))
                .isEqualTo(otherOwnerId);
        assertThat(notificationCount()).isEqualTo(1);
    }
}
