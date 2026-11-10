package com.pmo.supervise.source;

import com.pmo.supervise.IntegrationTestSupport;
import com.pmo.supervise.common.Actor;
import com.pmo.supervise.common.BusinessException;
import com.pmo.supervise.common.ErrorCode;
import com.pmo.supervise.common.Role;
import com.pmo.supervise.source.dto.SourceTypeRequest;
import com.pmo.supervise.source.dto.SourceTypeView;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.web.MockHttpServletRequest;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 来源配置业务护栏（TECHNICAL §4.1 / AC-1 / AC-4 / AC-6）。 */
class SourceTypeServiceIT extends IntegrationTestSupport {

    /** source_types 是配置数据，基座不清表；这里恢复成三条内置全启用，避免用例互相污染。 */
    @org.junit.jupiter.api.BeforeEach
    void resetSources() {
        jdbc.update("DELETE FROM source_types WHERE code NOT IN ('MEETING', 'PROJECT', 'DAILY')");
        jdbc.update("UPDATE source_types SET enabled = 1");
    }

    @Autowired
    private SourceTypeService service;

    private final MockHttpServletRequest http = new MockHttpServletRequest();

    private static BusinessException catchBusiness(Runnable runnable) {
        try {
            runnable.run();
        } catch (BusinessException ex) {
            return ex;
        }
        throw new AssertionError("预期抛出 BusinessException");
    }

    @Test
    void createGeneratesCustomCodeAndWritesAudit() {
        Actor admin = actor(adminId);

        SourceTypeView created = service.create(admin,
                new SourceTypeRequest("质量检查", SourceForm.NOTE, "#067647", null), http);

        assertThat(created.code()).matches("S\\d{5}");
        assertThat(created.name()).isEqualTo("质量检查");
        assertThat(created.form()).isEqualTo(SourceForm.NOTE);
        assertThat(created.color()).isEqualTo("#067647");
        assertThat(created.softColor()).isEqualTo("#ECFDF3");
        assertThat(created.enabled()).isTrue();
        assertThat(created.builtin()).isFalse();
        assertThat(created.locked()).isFalse();
        assertThat(created.sort()).isEqualTo(3);

        Integer audits = jdbc.queryForObject(
                "SELECT COUNT(*) FROM audit_logs WHERE action = 'SOURCE_TYPE_CREATED' AND target_id = ?",
                Integer.class, created.code());
        assertThat(audits).isEqualTo(1);
    }

    @Test
    void duplicateNameIsRejected() {
        Actor admin = actor(adminId);
        service.create(admin, new SourceTypeRequest("质量检查", SourceForm.NOTE, "#067647", null), http);

        BusinessException ex = catchBusiness(() -> service.create(admin,
                new SourceTypeRequest(" 质量检查 ", SourceForm.NOTE, "#3538CD", null), http));

        assertThat(ex.code()).isEqualTo(ErrorCode.SOURCE_NAME_DUPLICATE);
        assertThat(ex.code().status().value()).isEqualTo(409);
    }

    @Test
    void twentiethEnabledSourceIsAcceptedAndTwentyFirstRejected() {
        Actor admin = actor(adminId);
        for (int i = 0; i < 17; i++) {
            jdbc.update("""
                    INSERT INTO source_types (code, name, form, color, sort_no, enabled)
                    VALUES (?, ?, 'NOTE', '#067647', ?, 1)
                    """, "T" + i, "扩容" + i, 10 + i);
        }

        BusinessException ex = catchBusiness(() -> service.create(admin,
                new SourceTypeRequest("第二十一", SourceForm.NOTE, "#067647", null), http));
        assertThat(ex.code()).isEqualTo(ErrorCode.SOURCE_LIMIT_REACHED);
        assertThat(ex.code().status().value()).isEqualTo(409);

        // 上限只数启用中的来源：停用一个，新增就重新可用。
        service.setEnabled(admin, "T0", false, http);
        SourceTypeView created = service.create(admin,
                new SourceTypeRequest("第二十一", SourceForm.NOTE, "#067647", null), http);
        assertThat(created.code()).matches("S\\d{5}");
    }

    @Test
    void meetingSourceIsLocked() {
        Actor admin = actor(adminId);

        assertThat(service.isLocked(SourceCodes.MEETING)).isTrue();

        BusinessException disable = catchBusiness(() -> service.setEnabled(admin, SourceCodes.MEETING, false, http));
        assertThat(disable.code()).isEqualTo(ErrorCode.SOURCE_BUILTIN_PROTECTED);

        BusinessException remove = catchBusiness(() -> service.delete(admin, SourceCodes.MEETING, http));
        assertThat(remove.code()).isEqualTo(ErrorCode.SOURCE_BUILTIN_PROTECTED);

        BusinessException form = catchBusiness(() -> service.update(admin, SourceCodes.MEETING,
                new SourceTypeRequest("会议决议", SourceForm.NOTE, "#1B4D8F", 0), http));
        assertThat(form.code()).isEqualTo(ErrorCode.SOURCE_BUILTIN_PROTECTED);
    }

    @Test
    void referencedSourceCanOnlyBeDisabled() {
        Actor admin = actor(adminId);
        long owner = seedOwner("owner1", "张三");
        seedTask("引用项目来源", owner, LocalDate.of(2026, 11, 20), "PENDING", adminId);

        List<SourceTypeView> rows = service.list();
        SourceTypeView project = rows.stream().filter(r -> r.code().equals("PROJECT")).findFirst().orElseThrow();
        assertThat(project.usageCount()).isEqualTo(1);

        BusinessException ex = catchBusiness(() -> service.delete(admin, "PROJECT", http));
        assertThat(ex.code()).isEqualTo(ErrorCode.SOURCE_IN_USE);
        assertThat(ex.getMessage()).contains("1 条任务");

        SourceTypeView disabled = service.setEnabled(admin, "PROJECT", false, http);
        assertThat(disabled.enabled()).isFalse();
        Integer audits = jdbc.queryForObject(
                "SELECT COUNT(*) FROM audit_logs WHERE action = 'SOURCE_TYPE_DISABLED' AND target_id = 'PROJECT'",
                Integer.class);
        assertThat(audits).isEqualTo(1);
    }

    @Test
    void noteToMeetingConflictsWithUnboundTasks() {
        Actor admin = actor(adminId);
        long owner = seedOwner("owner1", "张三");
        seedTask("未绑定会议的项目任务", owner, LocalDate.of(2026, 11, 20), "PENDING", adminId);

        BusinessException ex = catchBusiness(() -> service.update(admin, "PROJECT",
                new SourceTypeRequest("项目任务", SourceForm.MEETING, "#6941C6", 1), http));

        assertThat(ex.code()).isEqualTo(ErrorCode.SOURCE_FORM_CONFLICT);
        assertThat(ex.getMessage()).contains("1 条任务");
    }

    @Test
    void disabledSourceIsRejectedOnWriteButStillKnown() {
        Actor admin = actor(adminId);
        service.setEnabled(admin, "DAILY", false, http);

        BusinessException disabled = catchBusiness(() -> service.requireEnabled("DAILY"));
        assertThat(disabled.code()).isEqualTo(ErrorCode.SOURCE_TYPE_DISABLED);
        assertThat(disabled.code().status().value()).isEqualTo(400);

        assertThat(service.requireKnown("DAILY").code()).isEqualTo("DAILY");
        assertThat(service.options()).filteredOn(o -> o.value().equals("DAILY"))
                .singleElement().satisfies(o -> assertThat(o.enabled()).isFalse());
    }

    @Test
    void unknownCodeIsInvalidNotMissing() {
        BusinessException ex = catchBusiness(() -> service.requireKnown("NOPE"));

        assertThat(ex.code()).isEqualTo(ErrorCode.SOURCE_TYPE_INVALID);
        assertThat(ex.code().status().value()).isEqualTo(400);
        assertThat(ex.fieldErrors()).isNotNull();
    }

    @Test
    void paletteOffersEightPresets() {
        assertThat(service.palette()).hasSize(8);
        assertThat(service.palette().get(0).color()).isEqualTo("#1B4D8F");
        assertThat(service.palette().get(6).color()).isEqualTo("#475467");
    }

    @Test
    void nonAdminCannotWrite() {
        long execId = seedExec("exec1", "李四");
        Actor exec = actor(execId);

        BusinessException ex = catchBusiness(() -> service.create(exec,
                new SourceTypeRequest("质量检查", SourceForm.NOTE, "#067647", null), http));

        assertThat(ex.code()).isEqualTo(ErrorCode.FORBIDDEN);
        assertThat(actor(adminId).role()).isEqualTo(Role.ADMIN);
    }
}
