package com.pmo.supervise.task;

import com.pmo.supervise.IntegrationTestSupport;
import com.pmo.supervise.common.TaskStatus;
import com.pmo.supervise.task.dto.TaskCounters;
import com.pmo.supervise.task.dto.TaskFilter;
import com.pmo.supervise.task.dto.TaskRow;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 100 条模拟数据的口径一致性（T12 自检 / Anti-goal #1）：
 * 任务池列表、计数、焦点列表必须来自同一套 WHERE 与同一个 today，不能出现第二套数字。
 */
class TaskQueryDaoConsistencyIT extends IntegrationTestSupport {

    @Autowired
    private TaskQueryDao queryDao;

    private final LocalDate today = LocalDate.of(2026, 11, 9);
    private long ownerId;

    @BeforeEach
    void seedHundredTasks() {
        ownerId = seedOwner("consistency-owner", "口径负责人");
        TaskStatus[] statuses = TaskStatus.values();
        for (int i = 0; i < 100; i++) {
            TaskStatus status = statuses[i % statuses.length];
            // 截止日覆盖：逾期 4 档、今天、明天、未来
            int offset = (i % 7) - 3;
            seedTask("口径任务 " + i, ownerId, today.plusDays(offset), status.name(), adminId);
        }
    }

    @Test
    @DisplayName("任务池 total 与 counters.total 一致，逾期/即将到期与 Java 规范逐条吻合")
    void poolNumbersShareOneRule() {
        TaskFilter all = new TaskFilter();
        TaskCounters counters = queryDao.counters(all, today);

        assertThat(counters.total()).isEqualTo(100);
        assertThat(queryDao.page(all, today, 1, 1).total()).isEqualTo(counters.total());

        List<TaskRow> rows = queryDao.page(all, today, 1, 100).items();
        assertThat(rows).hasSize(100);

        long expectedOverdue = rows.stream().filter(TaskRow::isOverdue).count();
        long expectedDueSoon = rows.stream().filter(TaskRow::dueSoon).count();
        long expectedUnfinished = rows.stream()
                .filter(row -> row.status().isUnfinished()).count();
        long expectedDone = rows.stream().filter(row -> row.status() == TaskStatus.DONE).count();
        long expectedPendingAccept = rows.stream()
                .filter(row -> row.status() == TaskStatus.PENDING_ACCEPT).count();

        assertThat(counters.overdue()).isEqualTo((int) expectedOverdue);
        assertThat(counters.dueSoon24h()).isEqualTo((int) expectedDueSoon);
        assertThat(counters.unfinished()).isEqualTo((int) expectedUnfinished);
        assertThat(counters.done()).isEqualTo((int) expectedDone);
        assertThat(counters.pendingAccept()).isEqualTo((int) expectedPendingAccept);

        // 每行的 is_overdue / overdue_days 都由同一 SQL 谓词产出
        for (TaskRow row : rows) {
            assertThat(row.isOverdue()).isEqualTo(OverdueSpec.isOverdue(row.status(), row.dueDate(), today));
            assertThat(row.overdueDays()).isEqualTo(OverdueSpec.overdueDays(row.status(), row.dueDate(), today));
            assertThat(row.dueSoon()).isEqualTo(OverdueSpec.isDueSoon(row.status(), row.dueDate(), today));
        }
    }

    @Test
    @DisplayName("筛选后的 total 与同一 WHERE 的 count 一致；overdue=true 只出逾期")
    void filteredTotalsStayConsistent() {
        TaskFilter overdueFilter = new TaskFilter();
        overdueFilter.setOverdue(true);
        TaskCounters all = queryDao.counters(new TaskFilter(), today);

        assertThat(queryDao.page(overdueFilter, today, 1, 100).total()).isEqualTo(all.overdue());

        TaskFilter statusFilter = new TaskFilter();
        statusFilter.setStatus("pending_accept");
        assertThat(queryDao.page(statusFilter, today, 1, 100).total()).isEqualTo(all.pendingAccept());

        TaskFilter ownerFilter = new TaskFilter();
        ownerFilter.setOwnerId(ownerId);
        assertThat(queryDao.page(ownerFilter, today, 1, 100).total()).isEqualTo(100);
    }

    @Test
    @DisplayName("焦点列表只含未完成，逾期排在前面，截止日升序")
    void focusListOrdering() {
        List<TaskRow> focus = queryDao.focus(new TaskFilter(), today, 10);

        assertThat(focus).hasSize(10);
        assertThat(focus).allMatch(row -> row.status().isUnfinished());
        assertThat(focus.get(0).isOverdue()).isTrue();

        boolean sawNonOverdue = false;
        for (TaskRow row : focus) {
            if (!row.isOverdue()) {
                sawNonOverdue = true;
            } else {
                assertThat(sawNonOverdue).as("逾期任务必须排在未逾期之前").isFalse();
            }
        }
    }

    @Test
    @DisplayName("分页不重不漏：三页拼起来正好 100 条且 id 唯一")
    void paginationCoversEveryRowOnce() {
        TaskFilter all = new TaskFilter();
        List<TaskRow> first = queryDao.page(all, today, 1, 40).items();
        List<TaskRow> second = queryDao.page(all, today, 2, 40).items();
        List<TaskRow> third = queryDao.page(all, today, 3, 40).items();

        assertThat(first).hasSize(40);
        assertThat(second).hasSize(40);
        assertThat(third).hasSize(20);
        assertThat(java.util.stream.Stream.of(first, second, third)
                .flatMap(List::stream)
                .map(TaskRow::id)
                .distinct()
                .count()).isEqualTo(100);
    }
}
