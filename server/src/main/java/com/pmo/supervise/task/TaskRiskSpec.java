package com.pmo.supervise.task;

import com.pmo.supervise.common.TaskStatus;
import com.pmo.supervise.task.dto.TaskRow;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 风险判定（r7 / AC-7）：逾期未完成，或待验收已满 {@link #PENDING_ACCEPT_DAYS} 天。
 *
 * <p>生产查询以 {@link TaskSql#AT_RISK} 为准；本类只提供同一口径的 Java 版本，
 * 供单元测试钉住边界（今天/昨天/已完成/待验收第 3 天）与会议任务区复用。
 * 天数常量只此一处，SQL 用字符串拼接引用它，禁止任何地方再写一个 3。
 */
public final class TaskRiskSpec {

    /** 待验收满 3 天即视为风险（第 3 天当天算风险，第 2 天不算）。 */
    public static final int PENDING_ACCEPT_DAYS = 3;

    private TaskRiskSpec() {
    }

    /** 待验收提交日 ≤ 该日期即为风险。 */
    public static LocalDate cutoff(LocalDate today) {
        return today == null ? null : today.minusDays(PENDING_ACCEPT_DAYS);
    }

    public static boolean isAtRisk(TaskStatus status, LocalDate dueDate, LocalDateTime submittedAt, LocalDate today) {
        if (status == null || today == null) {
            return false;
        }
        if (OverdueSpec.isOverdue(status, dueDate, today)) {
            return true;
        }
        return status == TaskStatus.PENDING_ACCEPT
                && submittedAt != null
                && !submittedAt.toLocalDate().isAfter(cutoff(today));
    }

    /**
     * 行级便捷判定：以服务端 SQL 算好的 {@code atRisk} 为准，逾期再兜一层，
     * 保证即便列缺失（旧库/局部查询）也不会把逾期任务漏成「无风险」。
     */
    public static boolean isAtRisk(TaskRow row, LocalDate today) {
        if (row == null) {
            return false;
        }
        return row.atRisk() || OverdueSpec.isOverdue(row.status(), row.dueDate(), today);
    }
}
