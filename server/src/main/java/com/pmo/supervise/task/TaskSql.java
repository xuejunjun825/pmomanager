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
}
