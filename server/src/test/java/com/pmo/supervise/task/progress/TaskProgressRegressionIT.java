package com.pmo.supervise.task.progress;

import com.pmo.supervise.IntegrationTestSupport;
import com.pmo.supervise.common.Actor;
import com.pmo.supervise.dashboard.MatrixService;
import com.pmo.supervise.dashboard.dto.MatrixFilter;
import com.pmo.supervise.dashboard.dto.MatrixView;
import com.pmo.supervise.task.TaskQueryDao;
import com.pmo.supervise.task.dto.TaskCounters;
import com.pmo.supervise.task.dto.TaskFilter;
import com.pmo.supervise.task.progress.dto.AddProgressRequest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 口径回归（TECHNICAL §4.6 / AC-12）：补进度不写 task_events、不动状态与看板计数，
 * 任务池计数器与矩阵合计在补进度前后完全一致。
 */
class TaskProgressRegressionIT extends IntegrationTestSupport {

    @Autowired
    private TaskProgressService progressService;

    @Autowired
    private TaskQueryDao queryDao;

    @Autowired
    private MatrixService matrixService;

    private final LocalDate today = LocalDate.of(2026, 11, 9);

    private TaskCounters counters() {
        return queryDao.counters(new TaskFilter(), today);
    }

    private MatrixView matrix() {
        return matrixService.load(new MatrixFilter(), today, actor(adminId));
    }

    @Test
    void progressDoesNotTouchStatusEventsOrTimestamps() {
        long owner = seedOwner("owner1", "张三");
        long taskId = seedTask("整理会议纪要", owner, LocalDate.of(2026, 11, 20), "IN_PROGRESS", adminId);
        String statusBefore = taskStatus(taskId);
        int eventsBefore = eventCount(taskId);
        String updatedBefore = jdbc.queryForObject(
                "SELECT DATE_FORMAT(updated_at, '%Y-%m-%d %H:%i:%s') FROM tasks WHERE id = ?",
                String.class, taskId);
        int eventsAfter = eventsBefore;

        progressService.add(taskId, actor(owner), new AddProgressRequest("补充一条进度", null));
        progressService.add(taskId, actor(owner), new AddProgressRequest("再补一条", null));

        assertThat(taskStatus(taskId)).isEqualTo(statusBefore);
        assertThat(eventCount(taskId)).isEqualTo(eventsAfter);
        assertThat(jdbc.queryForObject(
                "SELECT DATE_FORMAT(updated_at, '%Y-%m-%d %H:%i:%s') FROM tasks WHERE id = ?",
                String.class, taskId)).isEqualTo(updatedBefore);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM task_events WHERE task_id = ?", Integer.class, taskId))
                .isEqualTo(eventsBefore);
    }

    @Test
    void countersStayIdenticalAfterProgress() {
        long owner = seedOwner("owner1", "张三");
        long taskId = seedTask("整理会议纪要", owner, LocalDate.of(2026, 11, 30), "PENDING", adminId);
        seedTask("第二条任务", owner, LocalDate.of(2026, 12, 5), "IN_PROGRESS", adminId);
        TaskCounters before = counters();

        progressService.add(taskId, actor(owner), new AddProgressRequest("补充一条进度", null));

        TaskCounters after = counters();
        assertThat(after).isEqualTo(before);
        assertThat(after.total()).isEqualTo(2);
        assertThat(after.unfinished()).isEqualTo(2);
    }

    @Test
    void matrixTotalsStayIdenticalAfterProgress() {
        long owner = seedOwner("owner1", "张三");
        long meeting = seedMeeting("周会", adminId);
        long item = seedDecisionItem(meeting, "输出纪要", owner);
        long taskId = seedMeetingTask("会议任务", owner, LocalDate.of(2026, 11, 20), "IN_PROGRESS",
                adminId, meeting, item);
        MatrixView before = matrix();

        progressService.add(taskId, actor(owner), new AddProgressRequest("会议后补充", null));

        MatrixView after = matrix();
        assertThat(after.totals()).isEqualTo(before.totals());
        assertThat(after.rows()).isEqualTo(before.rows());
        assertThat(after.totals().total()).isEqualTo(1);
    }

    @Test
    void progressNeverCreatesTaskEventsOrStatusChanges() {
        long owner = seedOwner("owner1", "张三");
        long taskId = seedTask("整理会议纪要", owner, LocalDate.of(2026, 11, 20), "PENDING", adminId);
        Actor admin = actor(adminId);

        progressService.add(taskId, admin, new AddProgressRequest("管理员补一条", null));

        assertThat(eventCount(taskId)).isZero();
        assertThat(taskStatus(taskId)).isEqualTo("PENDING");
        assertThat(jdbc.queryForObject(
                "SELECT completed_at FROM tasks WHERE id = ?", Object.class, taskId)).isNull();
    }
}
