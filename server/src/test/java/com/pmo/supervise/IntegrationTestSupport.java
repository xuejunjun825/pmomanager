package com.pmo.supervise;

import com.pmo.supervise.common.Actor;
import com.pmo.supervise.common.Role;
import com.pmo.supervise.common.ShanghaiClock;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.time.LocalDate;
import java.util.Map;

/**
 * 集成测试基座：连本地 MySQL 的 supervise_test 库（Flyway 建表），每个用例前清空业务表并保留 admin。
 * 由于 T03 的登录接口不在本任务范围内，集成测试直接以 Actor 调用服务层。
 */
@SpringBootTest
@ActiveProfiles("test")
public abstract class IntegrationTestSupport {

    @Autowired
    protected JdbcTemplate jdbc;

    @Autowired
    protected ShanghaiClock clock;

    protected long adminId;

    @BeforeEach
    void resetDatabase() {
        jdbc.execute("DELETE FROM notifications");
        // 进度是 tasks 的子表（V5 的外键），必须比 tasks 先清。
        jdbc.execute("DELETE FROM task_progress_mentions");
        jdbc.execute("DELETE FROM task_progress");
        jdbc.execute("DELETE FROM urge_records");
        jdbc.execute("DELETE FROM task_events");
        jdbc.execute("DELETE FROM tasks");
        // themes 被 tasks.theme_id 引用（fk_tasks_theme，ON DELETE RESTRICT），必须比 tasks 后清；
        // 不放这里会让主题行跨测试类泄漏，各主题用例就得各自兜一遍，漏一个就污染后续用例。
        jdbc.execute("DELETE FROM themes");
        // projects 被 tasks.source_project_id 引用（ON DELETE RESTRICT），必须比 tasks 后清。
        jdbc.execute("DELETE FROM projects");
        jdbc.execute("DELETE FROM decision_items");
        jdbc.execute("DELETE FROM meetings");
        jdbc.execute("DELETE FROM job_runs");
        jdbc.execute("DELETE FROM audit_logs");
        // settings 行的 updated_by 指向 last saved admin：不清空会阻塞下面的用户清理（fk_settings_user）。
        jdbc.execute("UPDATE settings SET updated_by = NULL");
        jdbc.execute("DELETE FROM users WHERE username <> 'admin'");
        adminId = jdbc.queryForObject("SELECT id FROM users WHERE username = 'admin'", Long.class);
    }

    protected long seedUser(String username, String name, Role role, String status) {
        jdbc.update("""
                INSERT INTO users (username, password_hash, name, role, status, must_change_password)
                VALUES (?, ?, ?, ?, ?, 0)
                """, username, "$2a$10$J.jadwZEd6nV5yyUKWYiWe9sGMsAlj0OQt//TV9EQPdM1rY0GqmTO",
                name, role.name(), status);
        return jdbc.queryForObject("SELECT id FROM users WHERE username = ?", Long.class, username);
    }

    protected long seedOwner(String username, String name) {
        return seedUser(username, name, Role.OWNER, "ACTIVE");
    }

    protected long seedExec(String username, String name) {
        return seedUser(username, name, Role.EXEC, "ACTIVE");
    }

    protected long seedMeeting(String title, long createdBy) {
        jdbc.update("""
                INSERT INTO meetings (title, meeting_date, host, created_by)
                VALUES (?, ?, ?, ?)
                """, title, LocalDate.of(2026, 11, 1), "主持人", createdBy);
        return jdbc.queryForObject("SELECT MAX(id) FROM meetings", Long.class);
    }

    protected long seedDecisionItem(long meetingId, String description, long ownerId) {
        jdbc.update("""
                INSERT INTO decision_items (meeting_id, description, owner_id, due_date, sort_no)
                VALUES (?, ?, ?, ?, 0)
                """, meetingId, description, ownerId, LocalDate.of(2026, 11, 20));
        return jdbc.queryForObject("SELECT MAX(id) FROM decision_items", Long.class);
    }

    protected long seedTask(String title, long ownerId, LocalDate dueDate, String status, long dispatcherId) {
        jdbc.update("""
                INSERT INTO tasks (title, owner_id, due_date, source_type, source_note, status, dispatcher_id, priority)
                VALUES (?, ?, ?, 'PROJECT', '集成测试', ?, ?, 'MEDIUM')
                """, title, ownerId, dueDate, status, dispatcherId);
        return jdbc.queryForObject("SELECT MAX(id) FROM tasks", Long.class);
    }

    protected long seedMeetingTask(String title, long ownerId, LocalDate dueDate, String status,
                                   long dispatcherId, long meetingId, long decisionItemId) {
        jdbc.update("""
                INSERT INTO tasks (title, owner_id, due_date, source_type, source_meeting_id,
                                   source_decision_item_id, status, dispatcher_id, priority)
                VALUES (?, ?, ?, 'MEETING', ?, ?, ?, ?, 'MEDIUM')
                """, title, ownerId, dueDate, meetingId, decisionItemId, status, dispatcherId);
        return jdbc.queryForObject("SELECT MAX(id) FROM tasks", Long.class);
    }

    protected Actor actor(long userId) {
        Map<String, Object> row = jdbc.queryForMap("SELECT id, name, role FROM users WHERE id = ?", userId);
        return new Actor(((Number) row.get("id")).longValue(), Role.of((String) row.get("role")),
                (String) row.get("name"));
    }

    protected String taskStatus(long taskId) {
        return jdbc.queryForObject("SELECT status FROM tasks WHERE id = ?", String.class, taskId);
    }

    protected int eventCount(long taskId) {
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM task_events WHERE task_id = ?", Integer.class, taskId);
        return count == null ? 0 : count;
    }

    protected int notificationCount() {
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM notifications", Integer.class);
        return count == null ? 0 : count;
    }
}
