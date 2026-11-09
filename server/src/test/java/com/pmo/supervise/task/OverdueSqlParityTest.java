package com.pmo.supervise.task;

import com.pmo.supervise.IntegrationTestSupport;
import com.pmo.supervise.common.TaskStatus;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Java 规则（OverdueSpec）与 SQL 规则（TaskSql.OVERDUE / DUE_SOON）的对照测试（TECHNICAL §5.2）。
 * 15 组数据逐条钉住：改一侧不改另一侧，测试必然红。
 */
class OverdueSqlParityTest extends IntegrationTestSupport {

    private static final LocalDate TODAY = LocalDate.of(2026, 11, 9);

    @Autowired
    private NamedParameterJdbcTemplate namedJdbc;

    static Stream<Arguments> cases() {
        return Stream.of(
                Arguments.of(TaskStatus.PENDING, -5),
                Arguments.of(TaskStatus.PENDING, -1),
                Arguments.of(TaskStatus.PENDING, 0),
                Arguments.of(TaskStatus.PENDING, 1),
                Arguments.of(TaskStatus.IN_PROGRESS, -10),
                Arguments.of(TaskStatus.IN_PROGRESS, -2),
                Arguments.of(TaskStatus.IN_PROGRESS, 0),
                Arguments.of(TaskStatus.PENDING_ACCEPT, -3),
                Arguments.of(TaskStatus.PENDING_ACCEPT, 0),
                Arguments.of(TaskStatus.PENDING_ACCEPT, 3),
                Arguments.of(TaskStatus.DONE, -5),
                Arguments.of(TaskStatus.DONE, 0),
                Arguments.of(TaskStatus.CANCELLED, -5),
                Arguments.of(TaskStatus.CANCELLED, 1),
                Arguments.of(TaskStatus.PENDING, 2));
    }

    @ParameterizedTest(name = "SQL parity {0} due={1}d")
    @MethodSource("cases")
    void sqlPredicateMatchesJavaSpec(TaskStatus status, int dueOffset) {
        LocalDate due = TODAY.plusDays(dueOffset);
        long taskId = seedTask("对照任务 " + status + " " + dueOffset, adminId, due, status.name(), adminId);

        String sql = "SELECT CASE WHEN " + TaskSql.OVERDUE + " THEN 1 ELSE 0 END AS ov, "
                + "CASE WHEN " + TaskSql.OVERDUE + " THEN DATEDIFF(:today, t.due_date) ELSE 0 END AS days, "
                + "CASE WHEN " + TaskSql.DUE_SOON + " THEN 1 ELSE 0 END AS soon "
                + "FROM tasks t WHERE t.id = :id";
        Map<String, Object> params = new HashMap<>();
        params.put("today", TODAY);
        params.put("tomorrow", TODAY.plusDays(1));
        params.put("id", taskId);
        Map<String, Object> row = namedJdbc.queryForMap(sql, params);

        boolean sqlOverdue = ((Number) row.get("ov")).intValue() == 1;
        int sqlDays = ((Number) row.get("days")).intValue();
        boolean sqlDueSoon = ((Number) row.get("soon")).intValue() == 1;

        assertThat(sqlOverdue).isEqualTo(OverdueSpec.isOverdue(status, due, TODAY));
        assertThat(sqlDays).isEqualTo(OverdueSpec.overdueDays(status, due, TODAY));
        assertThat(sqlDueSoon).isEqualTo(OverdueSpec.isDueSoon(status, due, TODAY));
    }
}
