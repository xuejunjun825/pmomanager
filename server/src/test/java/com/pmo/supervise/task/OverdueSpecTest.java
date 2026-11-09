package com.pmo.supervise.task;

import com.pmo.supervise.common.TaskStatus;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.time.LocalDate;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 逾期规范的 15 组基准（AC 逾期判定 / TECHNICAL §5.2）。
 * SQL 侧同一规则由 OverdueSqlParityTest 逐条对照。
 */
class OverdueSpecTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 11, 9);

    static Stream<Arguments> cases() {
        return Stream.of(
                Arguments.of(TaskStatus.PENDING, -5, true, 5),
                Arguments.of(TaskStatus.PENDING, -1, true, 1),
                Arguments.of(TaskStatus.PENDING, 0, false, 0),
                Arguments.of(TaskStatus.PENDING, 1, false, 0),
                Arguments.of(TaskStatus.IN_PROGRESS, -10, true, 10),
                Arguments.of(TaskStatus.IN_PROGRESS, -2, true, 2),
                Arguments.of(TaskStatus.IN_PROGRESS, 0, false, 0),
                Arguments.of(TaskStatus.PENDING_ACCEPT, -3, true, 3),
                Arguments.of(TaskStatus.PENDING_ACCEPT, 3, false, 0),
                Arguments.of(TaskStatus.DONE, -5, false, 0),
                Arguments.of(TaskStatus.DONE, 0, false, 0),
                Arguments.of(TaskStatus.CANCELLED, -5, false, 0),
                Arguments.of(TaskStatus.CANCELLED, 1, false, 0),
                Arguments.of(TaskStatus.PENDING, 2, false, 0),
                Arguments.of(TaskStatus.PENDING_ACCEPT, 0, false, 0));
    }

    @ParameterizedTest(name = "{0} due={1}d → overdue={2}, days={3}")
    @MethodSource("cases")
    void overdueFollowsSingleSpec(TaskStatus status, int dueOffset, boolean expectedOverdue, int expectedDays) {
        LocalDate due = TODAY.plusDays(dueOffset);

        assertThat(OverdueSpec.isOverdue(status, due, TODAY)).isEqualTo(expectedOverdue);
        assertThat(OverdueSpec.overdueDays(status, due, TODAY)).isEqualTo(expectedDays);
        assertThat(OverdueSpec.overdueDays(due, TODAY)).isEqualTo(Math.max(-dueOffset, 0));
        assertThat(OverdueSpec.isDueSoon(status, due, TODAY))
                .isEqualTo(status.isUnfinished() && dueOffset == 1);
    }

    @ParameterizedTest(name = "isDueSoon boundary {0} due={1}d")
    @MethodSource("dueSoonCases")
    void dueSoonIsTomorrowOnly(TaskStatus status, int dueOffset, boolean expected) {
        assertThat(OverdueSpec.isDueSoon(status, TODAY.plusDays(dueOffset), TODAY)).isEqualTo(expected);
    }

    static Stream<Arguments> dueSoonCases() {
        return Stream.of(
                Arguments.of(TaskStatus.PENDING, 1, true),
                Arguments.of(TaskStatus.PENDING, 0, false),
                Arguments.of(TaskStatus.PENDING, 2, false),
                Arguments.of(TaskStatus.IN_PROGRESS, 1, true),
                Arguments.of(TaskStatus.DONE, 1, false),
                Arguments.of(TaskStatus.CANCELLED, 1, false));
    }
}
