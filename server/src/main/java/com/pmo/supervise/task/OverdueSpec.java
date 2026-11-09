package com.pmo.supervise.task;

import com.pmo.supervise.common.TaskStatus;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;

/**
 * 逾期判定（Java 侧规范实现，TECHNICAL §4.1）。
 * SQL 侧同规则见 {@link TaskSql#OVERDUE}，由 OverdueSqlParityTest 用 15 组数据钉住。
 */
public final class OverdueSpec {

    private OverdueSpec() {
    }

    public static boolean isOverdue(TaskStatus status, LocalDate dueDate, LocalDate today) {
        return status != null && status.isUnfinished() && dueDate != null && dueDate.isBefore(today);
    }

    public static int overdueDays(LocalDate dueDate, LocalDate today) {
        if (dueDate == null || today == null || !dueDate.isBefore(today)) {
            return 0;
        }
        return (int) ChronoUnit.DAYS.between(dueDate, today);
    }

    public static int overdueDays(TaskStatus status, LocalDate dueDate, LocalDate today) {
        return isOverdue(status, dueDate, today) ? overdueDays(dueDate, today) : 0;
    }

    /** T-1 预警：未完成且截止日 = 明天。 */
    public static boolean isDueSoon(TaskStatus status, LocalDate dueDate, LocalDate today) {
        return status != null && status.isUnfinished() && dueDate != null && dueDate.equals(today.plusDays(1));
    }
}
