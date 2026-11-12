package com.pmo.supervise.task;

import com.pmo.supervise.common.TaskStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 风险口径单测（r7 / AC-7）：风险 = 逾期未完成 ∪ 待验收满 3 天。
 *
 * <p>这里钉住的是「边界日」——待验收第 2 天不算、第 3 天算；逾期不论多久都算；
 * 已完成/已取消永不算。SQL 侧的同一口径由 {@link TaskSql#AT_RISK} 拼出，
 * 断言它引用常量而不是写死的 3，避免 SQL 与 Java 各写一个天数。
 */
class TaskRiskSpecTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 11, 12);

    @Test
    @DisplayName("已逾期未完成 → 风险")
    void overdueIsRisk() {
        assertThat(TaskRiskSpec.isAtRisk(TaskStatus.IN_PROGRESS, TODAY.minusDays(1), null, TODAY)).isTrue();
        assertThat(TaskRiskSpec.isAtRisk(TaskStatus.PENDING, TODAY.minusDays(30), null, TODAY)).isTrue();
    }

    @Test
    @DisplayName("今天到期不算逾期，也不算风险")
    void dueTodayIsNotRisk() {
        assertThat(TaskRiskSpec.isAtRisk(TaskStatus.PENDING, TODAY, null, TODAY)).isFalse();
        assertThat(TaskRiskSpec.isAtRisk(TaskStatus.IN_PROGRESS, TODAY.plusDays(3), null, TODAY)).isFalse();
    }

    @Test
    @DisplayName("已完成 / 已取消 → 永不风险，即使早已过期")
    void finishedIsNeverRisk() {
        assertThat(TaskRiskSpec.isAtRisk(TaskStatus.DONE, TODAY.minusDays(10), null, TODAY)).isFalse();
        assertThat(TaskRiskSpec.isAtRisk(TaskStatus.CANCELLED, TODAY.minusDays(10), null, TODAY)).isFalse();
    }

    @Test
    @DisplayName("待验收第 3 天算风险，第 2 天不算（边界只此一处）")
    void pendingAcceptBoundary() {
        LocalDate cutoff = TaskRiskSpec.cutoff(TODAY);
        assertThat(cutoff).isEqualTo(TODAY.minusDays(TaskRiskSpec.PENDING_ACCEPT_DAYS));

        assertThat(TaskRiskSpec.isAtRisk(TaskStatus.PENDING_ACCEPT, TODAY.plusDays(5),
                cutoff.atTime(9, 0), TODAY)).isTrue();
        assertThat(TaskRiskSpec.isAtRisk(TaskStatus.PENDING_ACCEPT, TODAY.plusDays(5),
                cutoff.plusDays(1).atTime(9, 0), TODAY)).isFalse();
        assertThat(TaskRiskSpec.isAtRisk(TaskStatus.PENDING_ACCEPT, TODAY.plusDays(5),
                TODAY.atTime(23, 59), TODAY)).isFalse();
    }

    @Test
    @DisplayName("待验收但未提交 / 空状态 → 不风险")
    void pendingAcceptWithoutSubmissionIsNotRisk() {
        assertThat(TaskRiskSpec.isAtRisk(TaskStatus.PENDING_ACCEPT, TODAY.plusDays(5), (LocalDateTime) null, TODAY))
                .isFalse();
        assertThat(TaskRiskSpec.isAtRisk(null, TODAY.minusDays(1), null, TODAY)).isFalse();
        assertThat(TaskRiskSpec.isAtRisk(TaskStatus.PENDING_ACCEPT, TODAY.plusDays(5),
                TODAY.atStartOfDay(), null)).isFalse();
    }

    @Test
    @DisplayName("SQL 谓词与 Java 共用同一个常量，且只复用 :today")
    void sqlReusesConstantAndTodayParam() {
        assertThat(TaskSql.AT_RISK)
                .contains("INTERVAL " + TaskRiskSpec.PENDING_ACCEPT_DAYS + " DAY")
                .contains(":today")
                .contains("PENDING_ACCEPT")
                .contains(TaskSql.OVERDUE);
        assertThat(TaskRiskSpec.PENDING_ACCEPT_DAYS).isEqualTo(3);
    }
}
