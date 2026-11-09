package com.pmo.supervise.task;

import com.pmo.supervise.common.Priority;
import com.pmo.supervise.common.Role;
import com.pmo.supervise.common.SourceType;
import com.pmo.supervise.common.TaskStatus;
import com.pmo.supervise.task.dto.TaskDetailRow;
import com.pmo.supervise.task.dto.TaskRow;
import com.pmo.supervise.task.dto.TaskSource;
import com.pmo.supervise.task.dto.UserRef;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;

/** 原生 SQL 行 → 读模型（TECHNICAL §4.4）。列名与 TaskQueryDao 的 SELECT 一一对应。 */
public final class TaskRowMapper {

    private TaskRowMapper() {
    }

    public static TaskRow mapRow(ResultSet rs) throws SQLException {
        return new TaskRow(
                rs.getLong("id"),
                rs.getString("title"),
                owner(rs),
                rs.getObject("due_date", java.time.LocalDate.class),
                source(rs),
                TaskStatus.of(rs.getString("status")),
                TaskStatus.of(rs.getString("status")).label(),
                rs.getBoolean("is_overdue"),
                rs.getInt("overdue_days"),
                rs.getBoolean("due_soon"),
                rs.getLong("urge_count"),
                rs.getObject("last_urged_at", LocalDateTime.class),
                rs.getObject("updated_at", LocalDateTime.class),
                rs.getObject("completed_at", LocalDateTime.class));
    }

    public static TaskDetailRow mapDetail(ResultSet rs) throws SQLException {
        TaskStatus status = TaskStatus.of(rs.getString("status"));
        return new TaskDetailRow(
                rs.getLong("id"),
                rs.getString("title"),
                owner(rs),
                rs.getObject("due_date", java.time.LocalDate.class),
                source(rs),
                status,
                status.label(),
                rs.getBoolean("is_overdue"),
                rs.getInt("overdue_days"),
                rs.getBoolean("due_soon"),
                rs.getLong("urge_count"),
                rs.getObject("last_urged_at", LocalDateTime.class),
                rs.getObject("updated_at", LocalDateTime.class),
                rs.getObject("completed_at", LocalDateTime.class),
                dispatcher(rs),
                Priority.of(rs.getString("priority")),
                rs.getString("completion_note"),
                rs.getString("completion_url"),
                rs.getObject("submitted_at", LocalDateTime.class),
                java.util.List.of(),
                com.pmo.supervise.task.dto.TaskPermissions.none(java.util.Map.of()));
    }

    private static UserRef owner(ResultSet rs) throws SQLException {
        Role role = null;
        try {
            role = Role.of(rs.getString("owner_role"));
        } catch (RuntimeException ignored) {
            // 读模型容错：角色缺失不影响任务展示
        }
        return new UserRef(rs.getLong("owner_id"), rs.getString("owner_name"), role);
    }

    private static UserRef dispatcher(ResultSet rs) throws SQLException {
        Role role = null;
        try {
            role = Role.of(rs.getString("dispatcher_role"));
        } catch (RuntimeException ignored) {
            // 读模型容错
        }
        return new UserRef(rs.getLong("dispatcher_id"), rs.getString("dispatcher_name"), role);
    }

    private static TaskSource source(ResultSet rs) throws SQLException {
        SourceType type = SourceType.of(rs.getString("source_type"));
        return new TaskSource(
                type,
                type.label(),
                rs.getObject("source_meeting_id", Long.class),
                rs.getString("meeting_title"),
                rs.getObject("source_decision_item_id", Long.class),
                rs.getString("source_note"));
    }
}
