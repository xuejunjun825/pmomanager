package com.pmo.supervise.project;

import com.pmo.supervise.IntegrationTestSupport;
import com.pmo.supervise.common.Actor;
import com.pmo.supervise.common.BusinessException;
import com.pmo.supervise.common.ErrorCode;
import com.pmo.supervise.common.Role;
import com.pmo.supervise.project.ProjectDao.ProjectRow;
import com.pmo.supervise.project.dto.ProjectOption;
import com.pmo.supervise.project.dto.ProjectRequest;
import com.pmo.supervise.project.dto.ProjectView;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.web.MockHttpServletRequest;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 项目服务层护栏、编码生成与审计（TECHNICAL §3.1 / §6.1 / AC-1 / AC-4 / AC-8）。 */
class ProjectServiceIT extends IntegrationTestSupport {

    @Autowired
    private ProjectService projects;

    private MockHttpServletRequest http() {
        return new MockHttpServletRequest();
    }

    private Actor admin() {
        long id = seedUser("prjadmin", "项目管理员", Role.ADMIN, "ACTIVE");
        return actor(id);
    }

    private ProjectView create(Actor actor, String name, Integer sort, Boolean enabled) {
        return projects.create(actor, new ProjectRequest(name, sort, enabled), http());
    }

    @Test
    void codesIncrementAndDefaultSortFollowsMax() {
        Actor admin = admin();

        ProjectView first = create(admin, "  智能工厂  ", null, null);
        assertThat(first.code()).matches("P\\d{5}");
        assertThat(first.code()).isEqualTo("P" + String.format(java.util.Locale.ROOT, "%05d", first.id()));
        assertThat(first.name()).isEqualTo("智能工厂");
        assertThat(first.sort()).isZero();
        assertThat(first.enabled()).isTrue();
        assertThat(first.usageCount()).isZero();

        ProjectView second = create(admin, "二期建设", null, null);
        assertThat(second.id()).isEqualTo(first.id() + 1);
        assertThat(second.code()).isEqualTo("P" + String.format(java.util.Locale.ROOT, "%05d", second.id()));
        assertThat(second.sort()).isEqualTo(1);

        List<ProjectOption> options = projects.options();
        assertThat(options).extracting(ProjectOption::name).containsExactly("智能工厂", "二期建设");
        assertThat(options.get(0).id()).isEqualTo(first.id());
        assertThat(options.get(0).sort()).isZero();
        assertThat(options.get(0).enabled()).isTrue();
    }

    @Test
    void everyWriteLeavesAnAuditRowWithBeforeAfterValues() {
        Actor admin = admin();
        ProjectView created = create(admin, "创建项目", 0, null);
        ProjectView updated = projects.update(admin, created.id(),
                new ProjectRequest("改名项目", 5, null), http());
        assertThat(updated.name()).isEqualTo("改名项目");

        projects.setEnabled(admin, created.id(), false, http());
        projects.setEnabled(admin, created.id(), true, http());
        projects.delete(admin, created.id(), http());

        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT action, target_id, detail FROM audit_logs
                 WHERE target_type = 'PROJECT' ORDER BY id
                """);
        assertThat(rows).extracting(row -> row.get("action")).containsExactly(
                ProjectService.AUDIT_CREATED, ProjectService.AUDIT_UPDATED,
                ProjectService.AUDIT_DISABLED, ProjectService.AUDIT_ENABLED,
                ProjectService.AUDIT_DELETED);
        assertThat(rows).extracting(row -> row.get("target_id")).containsOnly(created.code());
        assertThat((String) rows.get(0).get("detail")).contains("name=创建项目");
        assertThat((String) rows.get(1).get("detail")).contains("name=创建项目→改名项目");
        assertThat((String) rows.get(1).get("detail")).contains("sort=0→5");
        assertThat((String) rows.get(2).get("detail")).isEqualTo("enabled=1→0");
        assertThat((String) rows.get(3).get("detail")).isEqualTo("enabled=0→1");
    }

    @Test
    void unknownAndDisabledProjectFailWithDistinctCodes() {
        Actor admin = admin();
        assertThatThrownBy(() -> projects.requireKnown(987654L))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).code())
                        .isEqualTo(ErrorCode.PROJECT_INVALID));

        ProjectView created = create(admin, "停用项目", null, null);
        projects.setEnabled(admin, created.id(), false, http());

        assertThatThrownBy(() -> projects.requireEnabled(created.id()))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).code())
                        .isEqualTo(ErrorCode.PROJECT_DISABLED));
        // 停用不影响「存在」：历史任务仍能解析出项目名（AC-4）。
        assertThat(projects.requireKnown(created.id()).name()).isEqualTo("停用项目");
        assertThat(projects.requireKnown(created.id()).enabled()).isFalse();
    }

    @Test
    void duplicateNameIsRejectedCaseInsensitivelyAndAcrossDisabled() {
        Actor admin = admin();
        ProjectView created = create(admin, "Alpha 项目", null, null);
        projects.setEnabled(admin, created.id(), false, http());

        assertThatThrownBy(() -> create(admin, "alpha 项目", null, null))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> {
                    BusinessException be = (BusinessException) ex;
                    assertThat(be.code()).isEqualTo(ErrorCode.PROJECT_NAME_DUPLICATE);
                    assertThat(be.fieldErrors()).extracting("field").containsExactly("name");
                });
    }

    @Test
    void nonAdminCannotWriteButCanReadOptions() {
        Actor owner = actor(seedOwner("owner1", "张三"));
        assertThat(projects.options()).isEmpty();
        assertThatThrownBy(() -> create(owner, "越权项目", null, null))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).code()).isEqualTo(ErrorCode.FORBIDDEN));
    }

    @Test
    void deleteOfReferencedProjectReportsUsageWithoutLosingTheRow() {
        Actor admin = admin();
        ProjectView created = create(admin, "被引用项目", null, null);
        long owner = seedOwner("owner1", "张三");
        long taskId = seedTask("绑定任务的来源", owner, java.time.LocalDate.of(2026, 11, 20), "PENDING", adminId);
        jdbc.update("UPDATE tasks SET source_project_id = ?, source_note = NULL WHERE id = ?", created.id(), taskId);

        assertThatThrownBy(() -> projects.delete(admin, created.id(), http()))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> {
                    BusinessException be = (BusinessException) ex;
                    assertThat(be.code()).isEqualTo(ErrorCode.PROJECT_IN_USE);
                    assertThat(be.details()).containsEntry("taskCount", 1L);
                });
        assertThat(projects.requireKnown(created.id()).name()).isEqualTo("被引用项目");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM audit_logs WHERE action = ?",
                Integer.class, ProjectService.AUDIT_DELETED)).isZero();
    }

    /** 供上面的用例读回一行，确认实体形状（source_project_id 只在 tasks 上，projects 无该列）。 */
    @Test
    void projectRowShapeHasNoTaskColumns() {
        Actor admin = admin();
        ProjectView created = create(admin, "形状检查", null, null);
        ProjectRow row = projects.requireKnown(created.id());
        assertThat(row.code()).isEqualTo(created.code());
        assertThat(row.sort()).isZero();
        assertThat(row.enabled()).isTrue();
    }
}
