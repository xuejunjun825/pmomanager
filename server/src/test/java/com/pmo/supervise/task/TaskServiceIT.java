package com.pmo.supervise.task;

import com.pmo.supervise.IntegrationTestSupport;
import com.pmo.supervise.common.BusinessException;
import com.pmo.supervise.common.ErrorCode;
import com.pmo.supervise.common.Priority;
import com.pmo.supervise.source.SourceCodes;
import com.pmo.supervise.task.dto.CreateTaskRequest;
import com.pmo.supervise.task.dto.TaskDetailRow;
import com.pmo.supervise.task.dto.TaskPermissions;
import com.pmo.supervise.task.dto.UpdateTaskRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 任务创建校验与权限（TECHNICAL §4.3 / §5.4）：
 * 四字段 + 来源组合在服务层返回 422 带 fieldErrors；owner 越界统一 404。
 */
class TaskServiceIT extends IntegrationTestSupport {

    @Autowired
    private TaskService taskService;

    private long ownerId;
    private long otherOwnerId;
    private long meetingId;
    private long decisionItemId;
    private final LocalDate due = LocalDate.of(2026, 11, 20);

    @BeforeEach
    void seed() {
        ownerId = seedOwner("svc-owner", "负责人甲");
        otherOwnerId = seedOwner("svc-owner2", "负责人乙");
        meetingId = seedMeeting("产品周会", adminId);
        decisionItemId = seedDecisionItem(meetingId, "输出周会纪要", ownerId);
    }

    @Test
    @DisplayName("会议来源一次性转任务成功，来源快照可读")
    void createMeetingTask() {
        TaskDetailRow detail = taskService.create(actor(adminId), new CreateTaskRequest(
                "输出周会纪要", ownerId, due, SourceCodes.MEETING, meetingId, decisionItemId, null,
                Priority.HIGH, null), clock.today());

        assertThat(detail.source().type()).isEqualTo(SourceCodes.MEETING);
        assertThat(detail.source().meetingTitle()).isEqualTo("产品周会");
        assertThat(detail.source().decisionItemId()).isEqualTo(decisionItemId);
        assertThat(detail.owner().id()).isEqualTo(ownerId);
        assertThat(detail.dispatcher().id()).isEqualTo(adminId);
        assertThat(detail.status().name()).isEqualTo("PENDING");
    }

    @Test
    @DisplayName("会议来源缺会议或决议项 → 422 且 fieldErrors 指名")
    void meetingSourceRequiresBothIds() {
        assertThatThrownBy(() -> taskService.create(actor(adminId), new CreateTaskRequest(
                "缺会议", ownerId, due, SourceCodes.MEETING, null, null, null, null, null), clock.today()))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> {
                    BusinessException be = (BusinessException) ex;
                    assertThat(be.code()).isEqualTo(ErrorCode.VALIDATION_FAILED);
                    assertThat(be.fieldErrors()).extracting(f -> f.field())
                            .contains("sourceMeetingId", "sourceDecisionItemId");
                });
    }

    @Test
    @DisplayName("非会议来源必须写说明且不得带会议字段")
    void projectSourceNeedsNote() {
        assertThatThrownBy(() -> taskService.create(actor(adminId), new CreateTaskRequest(
                "没有来源", ownerId, due, "PROJECT", null, null, null, null, null), clock.today()))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).fieldErrors())
                        .extracting(f -> f.field()).contains("sourceNote"));

        assertThatThrownBy(() -> taskService.create(actor(adminId), new CreateTaskRequest(
                "来源冲突", ownerId, due, "PROJECT", meetingId, null, "说明", null, null), clock.today()))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).fieldErrors())
                        .extracting(f -> f.field()).contains("sourceMeetingId"));
    }

    @Test
    @DisplayName("同一决议项不能转出两条任务（uk_tasks_decision）")
    void decisionItemCanOnlyConvertOnce() {
        taskService.create(actor(adminId), new CreateTaskRequest(
                "输出周会纪要", ownerId, due, SourceCodes.MEETING, meetingId, decisionItemId, null, null, null),
                clock.today());

        assertThatThrownBy(() -> taskService.create(actor(adminId), new CreateTaskRequest(
                "重复转任务", ownerId, due, SourceCodes.MEETING, meetingId, decisionItemId, null, null, null),
                clock.today()))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).fieldErrors())
                        .extracting(f -> f.field()).contains("sourceDecisionItemId"));
    }

    @Test
    @DisplayName("停用用户不能作为负责人")
    void disabledOwnerIsRejected() {
        long disabled = seedUser("svc-disabled", "停用人", com.pmo.supervise.common.Role.OWNER, "DISABLED");

        assertThatThrownBy(() -> taskService.create(actor(adminId), new CreateTaskRequest(
                "派给停用人", disabled, due, "DAILY", null, null, "日常待办", null, null), clock.today()))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).fieldErrors())
                        .extracting(f -> f.field()).contains("ownerId"));
    }

    @Test
    @DisplayName("来源字段不可修改；改人改期必须走改派/改期")
    void sourceIsImmutable() {
        TaskDetailRow detail = taskService.create(actor(adminId), new CreateTaskRequest(
                "日常任务", ownerId, due, "DAILY", null, null, "晨会安排", null, null), clock.today());

        assertThatThrownBy(() -> taskService.update(detail.id(), actor(adminId),
                new UpdateTaskRequest("改来源", null, null, null, "新说明", "PROJECT", null, null, null),
                clock.today()))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).code())
                        .isEqualTo(ErrorCode.TASK_SOURCE_IMMUTABLE));

        assertThatThrownBy(() -> taskService.update(detail.id(), actor(adminId),
                new UpdateTaskRequest("改人", otherOwnerId, null, null, null, null, null, null, null),
                clock.today()))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).fieldErrors())
                        .extracting(f -> f.field()).contains("ownerId"));
    }

    @Test
    @DisplayName("owner 只能看自己的任务，越界 404")
    void ownerCannotViewOthersTask() {
        TaskDetailRow detail = taskService.create(actor(adminId), new CreateTaskRequest(
                "只属于甲", ownerId, due, "DAILY", null, null, "晨会安排", null, null), clock.today());

        assertThat(taskService.get(detail.id(), actor(ownerId), clock.today()).id()).isEqualTo(detail.id());

        assertThatThrownBy(() -> taskService.get(detail.id(), actor(otherOwnerId), clock.today()))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).code()).isEqualTo(ErrorCode.NOT_FOUND));
    }

    @Test
    @DisplayName("permissions 由服务端按角色与状态计算")
    void permissionsAreComputedServerSide() {
        TaskDetailRow detail = taskService.create(actor(adminId), new CreateTaskRequest(
                "权限检查", ownerId, due, "DAILY", null, null, "晨会安排", null, null), clock.today());

        TaskPermissions owner = taskService.permissions(taskService.require(detail.id()), actor(ownerId));
        assertThat(owner.canStart()).isTrue();
        assertThat(owner.canSubmit()).isTrue();
        assertThat(owner.canAccept()).isFalse();
        assertThat(owner.canReassign()).isFalse();

        long execId = seedExec("svc-exec", "督办人");
        TaskPermissions exec = taskService.permissions(taskService.require(detail.id()), actor(execId));
        assertThat(exec.canReassign()).isTrue();
        assertThat(exec.canReschedule()).isTrue();
        assertThat(exec.canUrge()).isTrue();
        assertThat(exec.canAccept()).isFalse();

        jdbc.update("UPDATE tasks SET status = 'PENDING_ACCEPT' WHERE id = ?", detail.id());
        TaskPermissions dispatcher = taskService.permissions(taskService.require(detail.id()), actor(adminId));
        assertThat(dispatcher.canAccept()).isTrue();
        assertThat(dispatcher.canReject()).isTrue();
    }
}
