package com.pmo.supervise.task;

import com.pmo.supervise.IntegrationTestSupport;
import com.pmo.supervise.common.Actor;
import com.pmo.supervise.common.BusinessException;
import com.pmo.supervise.common.ErrorCode;
import com.pmo.supervise.common.Priority;
import com.pmo.supervise.source.SourceForm;
import com.pmo.supervise.source.SourceTypeService;
import com.pmo.supervise.source.dto.SourceTypeRequest;
import com.pmo.supervise.source.dto.SourceTypeView;
import com.pmo.supervise.task.dto.CreateTaskRequest;
import com.pmo.supervise.task.dto.TaskDetailRow;
import com.pmo.supervise.task.dto.UpdateTaskRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.mock.web.MockHttpServletRequest;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 项目形态来源建任务（TECHNICAL §3.4 / §6.1 / AC-2 / AC-6 / AC-7）。
 *
 * <p>校验顺序是本文件的重点：结构冲突 400 → 缺选 422 → 停用/已删 400。三条用例各自只造一个
 * 失败原因，另有一条「同时缺项目又带说明」钉住优先级。
 */
class TaskSourceProjectIT extends IntegrationTestSupport {

    @Autowired
    private TaskService taskService;

    @Autowired
    private SourceTypeService sourceTypes;

    private final MockHttpServletRequest http = new MockHttpServletRequest();
    private final LocalDate today = LocalDate.of(2026, 11, 9);

    private long owner;
    private String projectSourceCode;
    private long projectId;
    private long disabledProjectId;

    @BeforeEach
    void seed() {
        jdbc.update("DELETE FROM source_types WHERE code NOT IN ('MEETING', 'PROJECT', 'DAILY')");
        jdbc.update("UPDATE source_types SET enabled = 1");
        owner = seedOwner("prj-owner", "项目负责人");
        Actor admin = actor(adminId);
        projectSourceCode = sourceTypes.create(admin,
                new SourceTypeRequest("工程项目", SourceForm.PROJECT, "#0B7A6E", 5), http).code();
        projectId = seedProject("P00001", "智能工厂", true);
        disabledProjectId = seedProject("P00002", "已停用项目", false);
    }

    @Test
    @DisplayName("项目形态建任务成功：行返回 form/projectId/projectName/projectEnabled")
    void projectTaskCarriesProjectView() {
        TaskDetailRow detail = taskService.create(actor(adminId),
                projectRequest("设备联调", projectId), today);

        assertThat(detail.source().form()).isEqualTo(SourceForm.PROJECT);
        assertThat(detail.source().projectId()).isEqualTo(projectId);
        assertThat(detail.source().projectName()).isEqualTo("智能工厂");
        assertThat(detail.source().projectEnabled()).isTrue();
        assertThat(detail.source().note()).isNull();
        assertThat(detail.source().meetingId()).isNull();
        assertThat(detail.source().decisionItemId()).isNull();
        assertThat(detail.source().typeLabel()).isEqualTo("工程项目");
    }

    @Test
    @DisplayName("项目来源缺选 → 422 指向 sourceProjectId")
    void missingProjectIs422() {
        assertThatThrownBy(() -> taskService.create(actor(adminId),
                projectRequest("没有项目", null), today))
                .isInstanceOfSatisfying(BusinessException.class, ex -> {
                    assertThat(ex.code()).isEqualTo(ErrorCode.VALIDATION_FAILED);
                    assertThat(ex.code().status().value()).isEqualTo(422);
                    assertThat(ex.fieldErrors()).extracting(f -> f.field()).contains("sourceProjectId");
                });
    }

    @Test
    @DisplayName("停用项目 400（PROJECT_DISABLED）、已删项目 400（PROJECT_INVALID）")
    void disabledOrDeletedProjectIs400() {
        assertThatThrownBy(() -> taskService.create(actor(adminId),
                projectRequest("停用项目", disabledProjectId), today))
                .isInstanceOfSatisfying(BusinessException.class, ex -> {
                    assertThat(ex.code()).isEqualTo(ErrorCode.PROJECT_DISABLED);
                    assertThat(ex.code().status().value()).isEqualTo(400);
                });

        assertThatThrownBy(() -> taskService.create(actor(adminId),
                projectRequest("不存在的项目", 999_999L), today))
                .isInstanceOfSatisfying(BusinessException.class, ex -> {
                    assertThat(ex.code()).isEqualTo(ErrorCode.PROJECT_INVALID);
                    assertThat(ex.code().status().value()).isEqualTo(400);
                });
    }

    @Test
    @DisplayName("项目来源携带说明或会议字段 → 400 PROJECT_SOURCE_CONFLICT（先于缺选 422）")
    void projectFormRejectsNoteAndMeetingFields() {
        CreateTaskRequest withNote = new CreateTaskRequest("带说明", owner, LocalDate.of(2026, 11, 20),
                projectSourceCode, null, null, "顺手写点说明", Priority.MEDIUM, projectId);
        assertThatThrownBy(() -> taskService.create(actor(adminId), withNote, today))
                .isInstanceOfSatisfying(BusinessException.class, ex -> {
                    assertThat(ex.code()).isEqualTo(ErrorCode.PROJECT_SOURCE_CONFLICT);
                    assertThat(ex.code().status().value()).isEqualTo(400);
                    assertThat(ex.fieldErrors()).extracting(f -> f.field()).contains("sourceNote");
                });

        long meeting = seedMeeting("周会", adminId);
        long item = seedDecisionItem(meeting, "输出纪要", owner);
        CreateTaskRequest withMeeting = new CreateTaskRequest("带会议", owner, LocalDate.of(2026, 11, 20),
                projectSourceCode, meeting, item, null, Priority.MEDIUM, projectId);
        assertThatThrownBy(() -> taskService.create(actor(adminId), withMeeting, today))
                .isInstanceOfSatisfying(BusinessException.class, ex -> {
                    assertThat(ex.code()).isEqualTo(ErrorCode.PROJECT_SOURCE_CONFLICT);
                    assertThat(ex.fieldErrors()).extracting(f -> f.field())
                            .contains("sourceMeetingId", "sourceDecisionItemId");
                });

        // 同时缺项目（422）与带说明（400）：结构冲突优先，保证 QA 能稳定复现 400。
        CreateTaskRequest both = new CreateTaskRequest("两者都错", owner, LocalDate.of(2026, 11, 20),
                projectSourceCode, null, null, "说明", Priority.MEDIUM, null);
        assertThatThrownBy(() -> taskService.create(actor(adminId), both, today))
                .isInstanceOfSatisfying(BusinessException.class,
                        ex -> assertThat(ex.code()).isEqualTo(ErrorCode.PROJECT_SOURCE_CONFLICT));
    }

    @Test
    @DisplayName("说明型/会议型来源带 sourceProjectId → 422 非项目来源不能关联项目")
    void nonProjectFormRejectsProjectId() {
        CreateTaskRequest noteForm = new CreateTaskRequest("说明型带项目", owner, LocalDate.of(2026, 11, 20),
                "PROJECT", null, null, "晨会安排", Priority.MEDIUM, projectId);
        assertThatThrownBy(() -> taskService.create(actor(adminId), noteForm, today))
                .isInstanceOfSatisfying(BusinessException.class, ex -> {
                    assertThat(ex.code()).isEqualTo(ErrorCode.VALIDATION_FAILED);
                    assertThat(ex.fieldErrors()).extracting(f -> f.field()).contains("sourceProjectId");
                });

        long meeting = seedMeeting("周会", adminId);
        long item = seedDecisionItem(meeting, "输出纪要", owner);
        CreateTaskRequest meetingForm = new CreateTaskRequest("会议型带项目", owner, LocalDate.of(2026, 11, 20),
                "MEETING", meeting, item, null, Priority.MEDIUM, projectId);
        assertThatThrownBy(() -> taskService.create(actor(adminId), meetingForm, today))
                .isInstanceOfSatisfying(BusinessException.class, ex -> {
                    assertThat(ex.code()).isEqualTo(ErrorCode.VALIDATION_FAILED);
                    assertThat(ex.fieldErrors()).extracting(f -> f.field()).contains("sourceProjectId");
                });
    }

    @Test
    @DisplayName("编辑任务带 sourceProjectId → TASK_SOURCE_IMMUTABLE")
    void projectIsImmutableAfterCreation() {
        long taskId = taskService.create(actor(adminId), projectRequest("不可改项目", projectId), today).id();

        assertThatThrownBy(() -> taskService.update(taskId, actor(adminId),
                new UpdateTaskRequest(null, null, null, null, null, null, null, null, projectId), today))
                .isInstanceOfSatisfying(BusinessException.class,
                        ex -> assertThat(ex.code()).isEqualTo(ErrorCode.TASK_SOURCE_IMMUTABLE));
    }

    @Test
    @DisplayName("库层 CHECK 拒绝「说明 + 项目」并存，服务层归一后不会出现")
    void databaseCheckRejectsNoteAndProject() {
        long taskId = taskService.create(actor(adminId), projectRequest("归一写法", projectId), today).id();
        assertThat(jdbc.queryForObject("SELECT source_note FROM tasks WHERE id = ?", String.class, taskId))
                .isNull();

        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO tasks (title, owner_id, due_date, source_type, source_note, source_project_id,
                                   status, dispatcher_id, priority)
                VALUES ('越界写法', ?, ?, ?, '说明与项目并存', ?, 'PENDING', ?, 'MEDIUM')
                """, owner, LocalDate.of(2026, 11, 20), projectSourceCode, projectId, adminId))
                // MySQL 把 CHECK 违约报成 3819，Spring 归到 UncategorizedSQLException：认约束名。
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("ck_tasks_source");
    }

    @Test
    @DisplayName("项目停用后历史任务仍可读，projectEnabled 反映最新状态")
    void disabledProjectStillReadable() {
        long taskId = taskService.create(actor(adminId), projectRequest("停用后仍可见", projectId), today).id();
        jdbc.update("UPDATE projects SET enabled = 0 WHERE id = ?", projectId);

        TaskDetailRow detail = taskService.get(taskId, actor(adminId), today);
        assertThat(detail.source().projectId()).isEqualTo(projectId);
        assertThat(detail.source().projectName()).isEqualTo("智能工厂");
        assertThat(detail.source().projectEnabled()).isFalse();
    }

    private CreateTaskRequest projectRequest(String title, Long projectId) {
        return new CreateTaskRequest(title, owner, LocalDate.of(2026, 11, 20), projectSourceCode,
                null, null, null, Priority.MEDIUM, projectId);
    }

    private long seedProject(String code, String name, boolean enabled) {
        jdbc.update("INSERT INTO projects (code, name, sort_no, enabled) VALUES (?, ?, 0, ?)",
                code, name, enabled ? 1 : 0);
        return jdbc.queryForObject("SELECT id FROM projects WHERE code = ?", Long.class, code);
    }
}
