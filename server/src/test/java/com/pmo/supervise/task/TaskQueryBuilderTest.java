package com.pmo.supervise.task;

import com.pmo.supervise.common.BusinessException;
import com.pmo.supervise.common.ErrorCode;
import com.pmo.supervise.project.ProjectDao;
import com.pmo.supervise.project.ProjectFilterSql;
import com.pmo.supervise.project.ProjectService;
import com.pmo.supervise.source.SourceForm;
import com.pmo.supervise.source.SourceTypeDao;
import com.pmo.supervise.source.SourceTypeService;
import com.pmo.supervise.task.dto.TaskFilter;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** WHERE / ORDER BY 编译（TECHNICAL §5.6）：条件全部走命名参数，非法输入 422。 */
class TaskQueryBuilderTest {

    /** 真实 Service 的轻量替身：本用例只关心「筛选编码经配置归一」这一件事。 */
    private final SourceTypeService sourceTypes = new SourceTypeService(null, null) {
        @Override
        public SourceTypeDao.SourceTypeConfig requireKnown(String code) {
            return new SourceTypeDao.SourceTypeConfig(1L, "MEETING", "会议决议",
                    SourceForm.MEETING, "#1B4D8F", 0, true);
        }
    };
    private final ProjectFilterSql projectFilter = new ProjectFilterSql(new ProjectService(null, null) {
        @Override
        public ProjectDao.ProjectRow requireKnown(long id) {
            if (id != 12L) {
                throw new BusinessException(ErrorCode.PROJECT_INVALID, "项目不存在或已删除");
            }
            return new ProjectDao.ProjectRow(12L, "P00012", "智能工厂", 0, true);
        }
    });
    private final TaskQueryBuilder builder = new TaskQueryBuilder(sourceTypes, projectFilter);
    private final LocalDate today = LocalDate.of(2026, 11, 9);

    @Test
    void alwaysBindsTodayAndTomorrow() {
        TaskQueryBuilder.TaskQuery query = builder.build(new TaskFilter(), today);
        assertThat(query.params()).containsEntry("today", today);
        assertThat(query.params()).containsEntry("tomorrow", today.plusDays(1));
        assertThat(query.where()).isEqualTo(" WHERE 1 = 1");
    }

    @Test
    void defaultOrderKeepsUnfinishedFirstAndDueDateAscending() {
        String order = builder.build(new TaskFilter(), today).orderBy();
        assertThat(order).contains("CASE WHEN t.status IN ('DONE','CANCELLED') THEN 1 ELSE 0 END");
        assertThat(order).contains("t.due_date END) ASC");
        assertThat(order).contains("t.completed_at END) DESC");
        assertThat(order).endsWith("t.id DESC");
    }

    @Test
    void filtersBecomeNamedParametersNotLiterals() {
        TaskFilter filter = new TaskFilter();
        filter.setStatus("in_progress");
        filter.setOwnerId(7L);
        filter.setSourceType("meeting");
        filter.setMeetingId(3L);
        filter.setOverdue(true);
        filter.setKeyword(" 汇报 ");

        TaskQueryBuilder.TaskQuery query = builder.build(filter, today);
        assertThat(query.where()).contains("t.status = :status", "t.owner_id = :ownerId",
                "t.source_type = :sourceType", "t.source_meeting_id = :meetingId",
                TaskSql.OVERDUE, "t.title LIKE :keyword", "u.name LIKE :keyword");
        assertThat(query.params()).containsEntry("status", "IN_PROGRESS")
                .containsEntry("ownerId", 7L)
                .containsEntry("sourceType", "MEETING")
                .containsEntry("meetingId", 3L)
                .containsEntry("keyword", "%汇报%");
    }

    @Test
    void overdueFalseMeansNotOverdue() {
        TaskFilter filter = new TaskFilter();
        filter.setOverdue(false);
        assertThat(builder.build(filter, today).where()).contains("AND NOT " + TaskSql.OVERDUE);
    }

    @Test
    void emptyProjectFilterLeavesWhereUntouched() {
        TaskFilter filter = new TaskFilter();
        filter.setProject("  ");
        assertThat(builder.build(filter, today).where()).isEqualTo(" WHERE 1 = 1");
    }

    @Test
    void projectIdBecomesNamedParameter() {
        TaskFilter filter = new TaskFilter();
        filter.setProject("12");
        TaskQueryBuilder.TaskQuery query = builder.build(filter, today);
        assertThat(query.where()).contains("t.source_project_id = :projectId");
        assertThat(query.params()).containsEntry("projectId", 12L);
    }

    @Test
    void unassignedProjectMatchesOnlyProjectFormWithoutProject() {
        TaskFilter filter = new TaskFilter();
        filter.setProject(ProjectFilterSql.UNASSIGNED);
        TaskQueryBuilder.TaskQuery query = builder.build(filter, today);
        assertThat(query.where())
                .contains("t.source_project_id IS NULL")
                .contains("s.form = 'PROJECT'");
        assertThat(query.params()).doesNotContainKey("projectId");
    }

    @Test
    void unknownProjectIdIs400() {
        TaskFilter filter = new TaskFilter();
        filter.setProject("999");
        assertThatThrownBy(() -> builder.build(filter, today))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).code()).isEqualTo(ErrorCode.PROJECT_INVALID));
    }

    @Test
    void projectSitsBetweenSourceTypeAndMeeting() {
        TaskFilter filter = new TaskFilter();
        filter.setSourceType("meeting");
        filter.setProject("12");
        filter.setMeetingId(3L);
        String where = builder.build(filter, today).where();
        assertThat(where.indexOf("t.source_type = :sourceType"))
                .isLessThan(where.indexOf("t.source_project_id = :projectId"));
        assertThat(where.indexOf("t.source_project_id = :projectId"))
                .isLessThan(where.indexOf("t.source_meeting_id = :meetingId"));
    }

    @Test
    void sortUpdatedDescIsAccepted() {
        TaskFilter filter = new TaskFilter();
        filter.setSort("updated_desc");
        assertThat(builder.build(filter, today).orderBy()).isEqualTo("ORDER BY t.updated_at DESC, t.id DESC");
    }

    @Test
    void invalidStatusIs422() {
        TaskFilter filter = new TaskFilter();
        filter.setStatus("WAITING");
        assertThatThrownBy(() -> builder.build(filter, today))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).code()).isEqualTo(ErrorCode.VALIDATION_FAILED));
    }

    @Test
    void unknownSortIs422() {
        TaskFilter filter = new TaskFilter();
        filter.setSort("bad_sort");
        assertThatThrownBy(() -> builder.build(filter, today))
                .isInstanceOf(BusinessException.class);
    }
}
