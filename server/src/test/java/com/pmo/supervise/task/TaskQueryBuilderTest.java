package com.pmo.supervise.task;

import com.pmo.supervise.common.BusinessException;
import com.pmo.supervise.common.ErrorCode;
import com.pmo.supervise.task.dto.TaskFilter;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** WHERE / ORDER BY 编译（TECHNICAL §5.6）：条件全部走命名参数，非法输入 422。 */
class TaskQueryBuilderTest {

    private final TaskQueryBuilder builder = new TaskQueryBuilder();
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
