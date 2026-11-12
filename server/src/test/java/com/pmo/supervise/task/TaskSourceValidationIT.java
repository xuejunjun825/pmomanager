package com.pmo.supervise.task;

import com.pmo.supervise.IntegrationTestSupport;
import com.pmo.supervise.common.Actor;
import com.pmo.supervise.common.BusinessException;
import com.pmo.supervise.common.ErrorCode;
import com.pmo.supervise.common.Priority;
import com.pmo.supervise.source.SourceCodes;
import com.pmo.supervise.source.SourceForm;
import com.pmo.supervise.source.SourceTypeService;
import com.pmo.supervise.source.dto.SourceTypeRequest;
import com.pmo.supervise.source.dto.SourceTypeView;
import com.pmo.supervise.task.dto.CreateTaskRequest;
import com.pmo.supervise.task.dto.TaskDetailRow;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.web.MockHttpServletRequest;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 建任务的来源形态校验（TECHNICAL §4.2 / AC-2 / AC-3）。 */
class TaskSourceValidationIT extends IntegrationTestSupport {

    @org.junit.jupiter.api.BeforeEach
    void resetSources() {
        jdbc.update("DELETE FROM source_types WHERE code NOT IN ('MEETING', 'PROJECT', 'DAILY')");
        jdbc.update("UPDATE source_types SET enabled = 1");
    }

    @Autowired
    private TaskService taskService;

    @Autowired
    private SourceTypeService sourceTypes;

    private final MockHttpServletRequest http = new MockHttpServletRequest();
    private final LocalDate today = LocalDate.of(2026, 11, 9);

    private long owner;

    private CreateTaskRequest request(String title, String sourceType, Long meetingId,
                                      Long decisionItemId, String note) {
        return request(title, sourceType, meetingId, decisionItemId, note, null);
    }

    private CreateTaskRequest request(String title, String sourceType, Long meetingId,
                                      Long decisionItemId, String note, Long projectId) {
        return new CreateTaskRequest(title, owner, LocalDate.of(2026, 11, 20), sourceType,
                meetingId, decisionItemId, note, Priority.MEDIUM, projectId, null, null);
    }

    @Test
    void meetingSourceCarriesConfiguredLabelAndPaletteColors() {
        Actor admin = actor(adminId);
        owner = seedOwner("owner1", "张三");
        long meeting = seedMeeting("周会", adminId);
        long item = seedDecisionItem(meeting, "输出纪要", owner);

        TaskDetailRow detail = taskService.create(admin,
                request("输出周会纪要", SourceCodes.MEETING, meeting, item, null), today);

        assertThat(detail.source().type()).isEqualTo(SourceCodes.MEETING);
        assertThat(detail.source().typeLabel()).isEqualTo("会议决议");
        assertThat(detail.source().color()).isEqualTo("#1B4D8F");
        assertThat(detail.source().softColor()).isEqualTo("#EAF1FA");
        assertThat(detail.source().borderColor()).isEqualTo("#C7D9EF");
        assertThat(detail.source().meetingId()).isEqualTo(meeting);
    }

    @Test
    void meetingSourceRequiresMeetingAndDecision() {
        Actor admin = actor(adminId);
        owner = seedOwner("owner1", "张三");

        assertThatThrownBy(() -> taskService.create(admin,
                request("缺会议", SourceCodes.MEETING, null, null, null), today))
                .isInstanceOfSatisfying(BusinessException.class, ex -> {
                    assertThat(ex.code()).isEqualTo(ErrorCode.VALIDATION_FAILED);
                    assertThat(ex.fieldErrors()).isNotEmpty();
                });
    }

    @Test
    void noteSourceRejectsMeetingFieldsAndNeedsNote() {
        Actor admin = actor(adminId);
        owner = seedOwner("owner1", "张三");
        long meeting = seedMeeting("周会", adminId);
        long item = seedDecisionItem(meeting, "输出纪要", owner);

        assertThatThrownBy(() -> taskService.create(admin,
                request("来源冲突", "PROJECT", meeting, null, "说明"), today))
                .isInstanceOf(BusinessException.class);

        assertThatThrownBy(() -> taskService.create(admin,
                request("没有来源", "PROJECT", null, null, null), today))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void customNoteSourceIsUsableAndKeepsItsName() {
        Actor admin = actor(adminId);
        owner = seedOwner("owner1", "张三");
        SourceTypeView custom = sourceTypes.create(admin,
                new SourceTypeRequest("质量检查", SourceForm.NOTE, "#067647", null), http);

        TaskDetailRow detail = taskService.create(admin,
                request("抽查交付物", custom.code(), null, null, "季度抽查"), today);

        assertThat(detail.source().type()).isEqualTo(custom.code());
        assertThat(detail.source().typeLabel()).isEqualTo("质量检查");
        assertThat(detail.source().color()).isEqualTo("#067647");
        assertThat(detail.source().note()).isEqualTo("季度抽查");
    }

    @Test
    void unknownSourceCodeIs400Not422() {
        Actor admin = actor(adminId);
        owner = seedOwner("owner1", "张三");

        assertThatThrownBy(() -> taskService.create(admin,
                request("野生来源", "GHOST", null, null, "说明"), today))
                .isInstanceOfSatisfying(BusinessException.class, ex -> {
                    assertThat(ex.code()).isEqualTo(ErrorCode.SOURCE_TYPE_INVALID);
                    assertThat(ex.code().status().value()).isEqualTo(400);
                });
    }

    @Test
    void disabledSourceCannotBeUsedForNewTasks() {
        Actor admin = actor(adminId);
        owner = seedOwner("owner1", "张三");
        sourceTypes.setEnabled(admin, "DAILY", false, http);

        assertThatThrownBy(() -> taskService.create(admin,
                request("停用来源", "DAILY", null, null, "晨会安排"), today))
                .isInstanceOfSatisfying(BusinessException.class, ex -> {
                    assertThat(ex.code()).isEqualTo(ErrorCode.SOURCE_TYPE_DISABLED);
                    assertThat(ex.code().status().value()).isEqualTo(400);
                });
    }

    @Test
    void sourceIsStillImmutableAfterCreation() {
        Actor admin = actor(adminId);
        owner = seedOwner("owner1", "张三");
        long taskId = taskService.create(admin,
                request("日常任务", "DAILY", null, null, "晨会安排"), today).id();

        assertThatThrownBy(() -> taskService.update(taskId, admin,
                new com.pmo.supervise.task.dto.UpdateTaskRequest(
                        "改来源", null, null, null, null, "PROJECT", null, null, null), today))
                .isInstanceOfSatisfying(BusinessException.class,
                        ex -> assertThat(ex.code()).isEqualTo(ErrorCode.TASK_SOURCE_IMMUTABLE));
    }
}
