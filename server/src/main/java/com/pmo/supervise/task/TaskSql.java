package com.pmo.supervise.task;

/**
 * 逾期/未完成/即将到期 SQL 片段（TECHNICAL §4.1）。
 * 任务池 WHERE、矩阵 SUM、看板 COUNT、扫描 SELECT 共用同一段文本，避免口径漂移。
 * 引用别名固定为 t，命名参数 today / tomorrow 必须由调用方提供。
 */
public final class TaskSql {

    private TaskSql() {
    }

    public static final String OVERDUE =
            "(t.status IN ('PENDING','IN_PROGRESS','PENDING_ACCEPT') AND t.due_date < :today)";

    public static final String NOT_FINISHED =
            "(t.status NOT IN ('DONE','CANCELLED'))";

    public static final String FINISHED =
            "(t.status IN ('DONE','CANCELLED'))";

    public static final String DUE_SOON =
            "(" + NOT_FINISHED + " AND t.due_date = :tomorrow)";

    /**
     * 风险 = 逾期未完成 ∪ 待验收已满 {@link TaskRiskSpec#PENDING_ACCEPT_DAYS} 天（r7 / AC-7）。
     * 天数只引用常量，SQL 与 Java 判定不会各写一个 3；只复用既有 :today，不新增命名参数，
     * 因此所有走 {@code TaskQueryDao.selectBase()} 的查询无需改参数注入。
     */
    public static final String AT_RISK =
            "(" + OVERDUE
            + " OR (t.status = 'PENDING_ACCEPT' AND t.submitted_at IS NOT NULL"
            + " AND DATE(t.submitted_at) <= DATE_SUB(:today, INTERVAL "
            + TaskRiskSpec.PENDING_ACCEPT_DAYS + " DAY)))";
}
