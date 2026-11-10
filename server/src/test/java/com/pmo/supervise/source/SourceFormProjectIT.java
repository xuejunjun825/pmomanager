package com.pmo.supervise.source;

import com.pmo.supervise.IntegrationTestSupport;
import com.pmo.supervise.auth.LoginAuditService;
import com.pmo.supervise.common.Actor;
import com.pmo.supervise.common.BusinessException;
import com.pmo.supervise.common.ErrorCode;
import com.pmo.supervise.source.SourceTypeDao.SourceTypeConfig;
import com.pmo.supervise.source.dto.SourceTypeRequest;
import com.pmo.supervise.source.dto.SourceTypeView;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.web.MockHttpServletRequest;

import java.time.LocalDate;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 来源形态三态（r4 TECHNICAL §3.2 / AC-6）。
 *
 * <p>两条契约：
 * <ol>
 *   <li>形态枚举新增 {@code PROJECT("需绑定项目")}，且**编码与形态同名不同义**：内置来源
 *       编码 {@code PROJECT}（「项目任务」）至今仍是 {@code NOTE} 形态，任何代码不得按编码推断形态。</li>
 *   <li>改形为会议型的前置条件是「原形态不是会议型」——项目型来源下没有会议的任务同样会撞
 *       {@code ck_tasks_source}，旧条件（仅 NOTE→MEETING）会让 PROJECT→MEETING 绕过检查变成 500。</li>
 * </ol>
 *
 * <p>形态三值在库层的放宽（{@code ck_source_form} 扩到 PROJECT）由 T01 的 V6 迁移负责；
 * 这里用覆写 DAO 的桩验证服务层判定顺序（护栏必须在写库之前挡住），并与真实库用例互为补充。
 */
class SourceFormProjectIT extends IntegrationTestSupport {

    @Autowired
    private LoginAuditService audit;

    private final MockHttpServletRequest http = new MockHttpServletRequest();

    /**
     * 覆写 DAO 的桩：只实现改形路径会碰到的六个方法，其余走父类但永远不会被调用
     * （传 null JdbcTemplate）。比 Mockito 桩更直白，也不受 JDK 25 内联 mock 限制。
     */
    private static final class StubDao extends SourceTypeDao {

        private final SourceTypeConfig config;
        private long unboundTaskCount;
        private long usageCount;

        private boolean unboundLookupCalled;
        private boolean updateCalled;
        private String updatedForm;

        private StubDao(SourceTypeConfig config) {
            super(null);
            this.config = config;
        }

        @Override
        public Optional<SourceTypeConfig> findByCode(String code) {
            return code != null && code.equals(config.code()) ? Optional.of(config) : Optional.empty();
        }

        @Override
        public boolean nameExists(String name, String excludeCode) {
            return false;
        }

        @Override
        public long countTasksWithoutMeeting(String code) {
            unboundLookupCalled = true;
            return unboundTaskCount;
        }

        @Override
        public long countTasksByCode(String code) {
            return usageCount;
        }

        @Override
        public void update(long id, String name, String form, String colorHex, int sort) {
            updateCalled = true;
            updatedForm = form;
        }
    }

    private static BusinessException catchBusiness(Runnable runnable) {
        try {
            runnable.run();
        } catch (BusinessException ex) {
            return ex;
        }
        throw new AssertionError("预期抛出 BusinessException");
    }

    private static SourceTypeConfig config(long id, String code, SourceForm form) {
        return new SourceTypeConfig(id, code, "项目任务", form, "#6941C6", 1, true);
    }

    private StubDao stubDao(SourceTypeConfig config) {
        return new StubDao(config);
    }

    private SourceTypeService serviceWith(SourceTypeDao dao) {
        return new SourceTypeService(dao, audit);
    }

    // —— 形态枚举契约 ——

    @Test
    void sourceFormCarriesThreeBusinessForms() {
        assertThat(SourceForm.values())
                .containsExactly(SourceForm.MEETING, SourceForm.PROJECT, SourceForm.NOTE);
        assertThat(SourceForm.PROJECT.label()).isEqualTo("需绑定项目");
        assertThat(SourceForm.MEETING.label()).isEqualTo("需绑定会议");
        assertThat(SourceForm.NOTE.label()).isEqualTo("自由说明");
        assertThat(SourceForm.of("PROJECT")).isEqualTo(SourceForm.PROJECT);
    }

    /**
     * AC-6 的「一词三义」隔离：内置来源编码 PROJECT 是「项目任务」，形态仍是 NOTE。
     * 若有人按编码猜形态，这条会先红。
     */
    @Test
    void builtinProjectSourceKeepsNoteForm() {
        String storedForm = jdbc.queryForObject(
                "SELECT form FROM source_types WHERE code = 'PROJECT'", String.class);
        assertThat(storedForm).isEqualTo("NOTE");

        SourceForm form = SourceForm.of(storedForm);
        assertThat(form).isEqualTo(SourceForm.NOTE);
        // 编码 PROJECT 与形态 PROJECT 同名不同义：两者不是同一个东西。
        assertThat(form).isNotEqualTo(SourceForm.of("PROJECT"));

        SourceTypeService real = serviceWith(new SourceTypeDao(jdbc));
        assertThat(real.requireKnown("PROJECT").form()).isEqualTo(SourceForm.NOTE);
    }

    // —— 改形护栏（目标 = MEETING） ——

    /** 旧条件（仅 NOTE）下这条会放行并写库，即本轮要堵的 500 漏洞。 */
    @Test
    void projectToMeetingIsBlockedWhenTasksHaveNoMeeting() {
        StubDao dao = stubDao(config(7, "CUSTOM1", SourceForm.PROJECT));
        dao.unboundTaskCount = 3;
        SourceTypeService service = serviceWith(dao);
        Actor admin = actor(adminId);

        BusinessException ex = catchBusiness(() -> service.update(admin, "CUSTOM1",
                new SourceTypeRequest("项目任务", SourceForm.MEETING, "#6941C6", 1), http));

        assertThat(ex.code()).isEqualTo(ErrorCode.SOURCE_FORM_CONFLICT);
        assertThat(ex.code().status().value()).isEqualTo(409);
        assertThat(ex.getMessage()).contains("3 条任务");
        assertThat(ex.details()).containsEntry("taskCount", 3L);
        // 护栏在读库之后、写库之前：一行都不能改。
        assertThat(dao.unboundLookupCalled).isTrue();
        assertThat(dao.updateCalled).isFalse();
    }

    @Test
    void projectToMeetingPassesWhenSourceHasNoUnboundTasks() {
        StubDao dao = stubDao(config(7, "CUSTOM1", SourceForm.PROJECT));
        dao.unboundTaskCount = 0;
        SourceTypeService service = serviceWith(dao);

        SourceTypeView updated = service.update(actor(adminId), "CUSTOM1",
                new SourceTypeRequest("项目任务", SourceForm.MEETING, "#6941C6", 1), http);

        assertThat(updated.form()).isEqualTo(SourceForm.MEETING);
        assertThat(dao.updateCalled).isTrue();
        assertThat(dao.updatedForm).isEqualTo("MEETING");
    }

    /** 原有 NOTE→MEETING 护栏不得因放宽前置条件而丢失（真实库 + 真实 DAO）。 */
    @Test
    void noteToMeetingStillBlockedAgainstRealDatabase() {
        long owner = seedOwner("owner1", "张三");
        seedTask("升级前的项目任务", owner, LocalDate.of(2026, 11, 20), "PENDING", adminId);
        SourceTypeService real = serviceWith(new SourceTypeDao(jdbc));

        BusinessException ex = catchBusiness(() -> real.update(actor(adminId), "PROJECT",
                new SourceTypeRequest("项目任务", SourceForm.MEETING, "#6941C6", 1), http));

        assertThat(ex.code()).isEqualTo(ErrorCode.SOURCE_FORM_CONFLICT);
        assertThat(ex.getMessage()).contains("1 条任务");
        assertThat(jdbc.queryForObject(
                "SELECT form FROM source_types WHERE code = 'PROJECT'", String.class)).isEqualTo("NOTE");
    }

    /** 目标不是会议型时不该白查一次、更不该误报冲突。 */
    @Test
    void leavingMeetingFormDoesNotConsultUnboundMeetingTasks() {
        StubDao dao = stubDao(config(8, "CUSTOM2", SourceForm.MEETING));
        dao.usageCount = 5;
        SourceTypeService service = serviceWith(dao);

        SourceTypeView updated = service.update(actor(adminId), "CUSTOM2",
                new SourceTypeRequest("日常待办", SourceForm.NOTE, "#475467", 2), http);

        assertThat(updated.form()).isEqualTo(SourceForm.NOTE);
        assertThat(dao.unboundLookupCalled).isFalse();
        assertThat(dao.updatedForm).isEqualTo("NOTE");
    }

    /** 内置会议来源的形态锁定不受新枚举影响（既有 AC-1 行为不回归）。 */
    @Test
    void builtinMeetingSourceStaysFormLocked() {
        StubDao dao = stubDao(config(1, "MEETING", SourceForm.MEETING));
        SourceTypeService service = serviceWith(dao);

        BusinessException ex = catchBusiness(() -> service.update(actor(adminId), "MEETING",
                new SourceTypeRequest("会议决议", SourceForm.PROJECT, "#1B4D8F", 0), http));

        assertThat(ex.code()).isEqualTo(ErrorCode.SOURCE_BUILTIN_PROTECTED);
        assertThat(dao.updateCalled).isFalse();
    }
}
