package com.pmo.supervise.source;

import com.pmo.supervise.IntegrationTestSupport;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 升级兼容（TECHNICAL §3.1 / AC-3）：V4 建表即种子三条内置，r1 已有任务的 source_type
 * 原值（MEETING/PROJECT/DAILY）在加 FK 后仍然合法，库层继续守住「会议两引用与说明互斥」。
 */
class SourceUpgradeIT extends IntegrationTestSupport {

    /** 只留 V4 种子的三条内置，升级断言才可重复。 */
    @org.junit.jupiter.api.BeforeEach
    void resetSources() {
        jdbc.update("DELETE FROM source_types WHERE code NOT IN ('MEETING', 'PROJECT', 'DAILY')");
        jdbc.update("UPDATE source_types SET enabled = 1");
    }

    @Test
    void v4AndV5AreAppliedOnceAndSucceed() {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT version, success FROM flyway_schema_history
                WHERE version IN ('4', '5') ORDER BY installed_rank
                """);

        assertThat(rows).hasSize(2);
        assertThat(rows).allSatisfy(row -> assertThat(row.get("success")).isIn(true, 1, (byte) 1));
    }

    @Test
    void builtinSeedsMatchLegacyEnumValues() {
        List<String> codes = jdbc.queryForList(
                "SELECT code FROM source_types ORDER BY sort_no, id", String.class);

        assertThat(codes).containsExactly("MEETING", "PROJECT", "DAILY");
        assertThat(jdbc.queryForObject(
                "SELECT color FROM source_types WHERE code = 'MEETING'", String.class))
                .isEqualTo("#1B4D8F");
        assertThat(jdbc.queryForObject(
                "SELECT name FROM source_types WHERE code = 'PROJECT'", String.class))
                .isEqualTo("项目任务");
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM source_types WHERE enabled = 1", Integer.class)).isEqualTo(3);
    }

    @Test
    void legacyTaskRowsStillInsertWithForeignKeysOn() {
        long owner = seedOwner("owner1", "张三");
        long meeting = seedMeeting("r1 会议", adminId);
        long item = seedDecisionItem(meeting, "r1 决议", owner);

        long meetingTask = seedMeetingTask("升级前的会议任务", owner,
                LocalDate.of(2026, 11, 20), "PENDING", adminId, meeting, item);
        long dailyTask = seedTask("升级前的日常任务", owner,
                LocalDate.of(2026, 11, 21), "PENDING", adminId);

        assertThat(taskStatus(meetingTask)).isEqualTo("PENDING");
        assertThat(taskStatus(dailyTask)).isEqualTo("PENDING");
        assertThat(jdbc.queryForObject(
                "SELECT source_type FROM tasks WHERE id = ?", String.class, meetingTask))
                .isEqualTo("MEETING");
    }

    @Test
    void unknownSourceCodeIsRejectedByTheForeignKey() {
        long owner = seedOwner("owner1", "张三");

        assertThatThrownBy(() -> jdbc.update("""
                        INSERT INTO tasks (title, owner_id, due_date, source_type, source_note,
                                           status, priority, dispatcher_id)
                        VALUES ('野生来源', ?, ?, 'GHOST', '说明', 'PENDING', 'MEDIUM', ?)
                        """, owner, LocalDate.of(2026, 11, 20), adminId))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void checkConstraintStillSeparatesMeetingAndNoteSources() {
        long owner = seedOwner("owner1", "张三");
        long meeting = seedMeeting("r1 会议", adminId);
        long item = seedDecisionItem(meeting, "r1 决议", owner);

        assertThatThrownBy(() -> jdbc.update("""
                        INSERT INTO tasks (title, owner_id, due_date, source_type, source_meeting_id,
                                           source_decision_item_id, source_note, status, priority, dispatcher_id)
                        VALUES ('会议来源带说明', ?, ?, 'MEETING', ?, ?, '两套字段', 'PENDING', 'MEDIUM', ?)
                        """, owner, LocalDate.of(2026, 11, 20), meeting, item, adminId))
                .isInstanceOf(org.springframework.dao.DataAccessException.class)
                .hasMessageContaining("ck_tasks_source");
    }

    @Test
    void notificationProgressColumnExistsAfterV5() {
        Integer columns = jdbc.queryForObject("""
                SELECT COUNT(*) FROM information_schema.columns
                WHERE table_schema = DATABASE() AND table_name = 'notifications'
                  AND column_name = 'progress_id'
                """, Integer.class);

        assertThat(columns).isEqualTo(1);
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM information_schema.tables
                WHERE table_schema = DATABASE()
                  AND table_name IN ('task_progress', 'task_progress_mentions')
                """, Integer.class)).isEqualTo(2);
    }
}
