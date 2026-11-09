package com.pmo.supervise.task;

import com.pmo.supervise.common.TaskStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** 5 状态 18 条合法迁移 + 10 组非法组合必须被拒绝（TECHNICAL §4.2）。 */
class TaskActionTest {

    @Test
    @DisplayName("状态机恰好 18 条合法迁移，且每条都落在 5 个状态内")
    void hasExactlyEighteenLegalEdges() {
        assertThat(TaskAction.legalEdges()).hasSize(18);
    }

    @Test
    @DisplayName("18 条合法边逐条通过 allows()，目标状态符合规范")
    void everyLegalEdgeIsAllowed() {
        assertThat(TaskAction.START.target(TaskStatus.PENDING)).isEqualTo(TaskStatus.IN_PROGRESS);
        assertThat(TaskAction.SUBMIT.target(TaskStatus.PENDING)).isEqualTo(TaskStatus.PENDING_ACCEPT);
        assertThat(TaskAction.SUBMIT.target(TaskStatus.IN_PROGRESS)).isEqualTo(TaskStatus.PENDING_ACCEPT);
        assertThat(TaskAction.ACCEPT.target(TaskStatus.PENDING_ACCEPT)).isEqualTo(TaskStatus.DONE);
        assertThat(TaskAction.REJECT.target(TaskStatus.PENDING_ACCEPT)).isEqualTo(TaskStatus.IN_PROGRESS);
        assertThat(TaskAction.CANCEL.target(TaskStatus.PENDING_ACCEPT)).isEqualTo(TaskStatus.CANCELLED);
        assertThat(TaskAction.RESTORE.target(TaskStatus.CANCELLED)).isEqualTo(TaskStatus.PENDING);
        assertThat(TaskAction.FORCE_COMPLETE.target(TaskStatus.IN_PROGRESS)).isEqualTo(TaskStatus.DONE);

        for (String edge : TaskAction.legalEdges()) {
            String[] parts = edge.split(":");
            TaskAction action = TaskAction.valueOf(parts[0]);
            TaskStatus from = TaskStatus.valueOf(parts[1]);
            assertThat(action.allows(from)).as(edge).isTrue();
        }
    }

    @Test
    @DisplayName("10 组未定义的状态×动作组合一律 409（allows=false）")
    void tenIllegalCombinationsAreRejected() {
        List<String> illegal = List.of(
                "START:IN_PROGRESS",
                "START:PENDING_ACCEPT",
                "START:DONE",
                "ACCEPT:PENDING",
                "ACCEPT:IN_PROGRESS",
                "ACCEPT:CANCELLED",
                "RESTORE:DONE",
                "RESTORE:PENDING",
                "FORCE_COMPLETE:DONE",
                "CANCEL:CANCELLED");
        assertThat(illegal).hasSize(10);
        for (String pair : illegal) {
            String[] parts = pair.split(":");
            TaskAction action = TaskAction.valueOf(parts[0]);
            TaskStatus from = TaskStatus.valueOf(parts[1]);
            assertThat(action.allows(from)).as(pair).isFalse();
        }
    }

    @Test
    @DisplayName("DONE 没有出边，CANCELLED 只能恢复")
    void terminalStatusesHaveNoOutgoingTransition() {
        for (TaskAction action : TaskAction.values()) {
            if (action == TaskAction.UPDATE) {
                continue;
            }
            assertThat(action.allows(TaskStatus.DONE)).as(action.name()).isFalse();
        }
        assertThat(TaskAction.RESTORE.allows(TaskStatus.CANCELLED)).isTrue();
        assertThat(TaskAction.RESTORE.allows(TaskStatus.PENDING)).isFalse();
    }
}
