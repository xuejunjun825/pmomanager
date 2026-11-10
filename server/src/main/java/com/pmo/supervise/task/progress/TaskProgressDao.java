package com.pmo.supervise.task.progress;

import com.pmo.supervise.common.Role;
import com.pmo.supervise.task.dto.UserRef;
import com.pmo.supervise.task.progress.dto.ProgressRow;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 进度与 @ 关系的原生 SQL（TECHNICAL §4.4）。
 *
 * <p>进度 append-only，@ 关系单独成表才能反查「我可见的任务」；
 * 关注点留在这两张新表里，TaskQueryDao 继续只读任务模型。
 */
@Repository
public class TaskProgressDao {

    private static final String SELECT_ROW = """
            SELECT p.id, p.content, p.author_id, u.name AS author_name, u.role AS author_role, p.created_at
            FROM task_progress p
            JOIN users u ON u.id = p.author_id
            """;

    private final JdbcTemplate jdbc;

    public TaskProgressDao(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 200 条上限的计数口径。 */
    public long countByTask(long taskId) {
        Long count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM task_progress WHERE task_id = ?", Long.class, taskId);
        return count == null ? 0 : count;
    }

    public long countAll(long taskId) {
        return countByTask(taskId);
    }

    public long insert(long taskId, long authorId, String content) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement ps = connection.prepareStatement(
                    "INSERT INTO task_progress (task_id, author_id, content) VALUES (?, ?, ?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setLong(1, taskId);
            ps.setLong(2, authorId);
            ps.setString(3, content);
            return ps;
        }, keyHolder);
        Number key = keyHolder.getKey();
        if (key == null) {
            throw new IllegalStateException("task_progress 未返回自增主键");
        }
        return key.longValue();
    }

    public void insertMentions(long progressId, List<Long> userIds) {
        for (Long userId : userIds) {
            jdbc.update(
                    "INSERT INTO task_progress_mentions (progress_id, user_id) VALUES (?, ?)",
                    progressId, userId);
        }
    }

    /** 时间线倒序分页；beforeId 为上一页最旧一条的 id。 */
    public List<ProgressRow> page(long taskId, int limit, Long beforeId) {
        List<ProgressRow> rows;
        if (beforeId == null) {
            rows = jdbc.query(SELECT_ROW + " WHERE p.task_id = ? ORDER BY p.id DESC LIMIT ?",
                    (rs, i) -> map(rs), taskId, limit);
        } else {
            rows = jdbc.query(SELECT_ROW + " WHERE p.task_id = ? AND p.id < ? ORDER BY p.id DESC LIMIT ?",
                    (rs, i) -> map(rs), taskId, beforeId, limit);
        }
        return withMentions(rows);
    }

    public Optional<ProgressRow> findById(long id) {
        List<ProgressRow> rows = jdbc.query(SELECT_ROW + " WHERE p.id = ?", (rs, i) -> map(rs), id);
        if (rows.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(withMentions(rows).get(0));
    }

    /** 我是否在这条任务里被 @ 过——详情可见性的唯一判据（AC-7）。 */
    public boolean isMentioned(long taskId, long userId) {
        Integer hit = jdbc.query(
                """
                        SELECT 1 FROM task_progress_mentions pm
                        JOIN task_progress p ON p.id = pm.progress_id
                        WHERE p.task_id = ? AND pm.user_id = ?
                        LIMIT 1
                        """,
                (rs, i) -> rs.getInt(1), taskId, userId).stream().findFirst().orElse(null);
        return hit != null;
    }

    /** 只返回启用成员的 id：@ 校验在 Service 一处判定。 */
    public List<Long> activeUserIds(List<Long> userIds) {
        if (userIds.isEmpty()) {
            return List.of();
        }
        String placeholders = String.join(",", java.util.Collections.nCopies(userIds.size(), "?"));
        return jdbc.query(
                "SELECT id FROM users WHERE status = 'ACTIVE' AND id IN (" + placeholders + ")",
                (rs, i) -> rs.getLong(1), userIds.toArray());
    }

    private List<ProgressRow> withMentions(List<ProgressRow> rows) {
        if (rows.isEmpty()) {
            return rows;
        }
        List<Long> ids = rows.stream().map(ProgressRow::id).toList();
        String placeholders = String.join(",", java.util.Collections.nCopies(ids.size(), "?"));
        List<Map.Entry<Long, UserRef>> pairs = jdbc.query("""
                        SELECT pm.progress_id, u.id, u.name, u.role
                        FROM task_progress_mentions pm
                        JOIN users u ON u.id = pm.user_id
                        WHERE pm.progress_id IN (""" + " " + placeholders + ") ORDER BY u.name, u.id",
                (rs, i) -> {
                    Role role = null;
                    try {
                        role = Role.of(rs.getString("role"));
                    } catch (RuntimeException ignored) {
                        // 展示容错
                    }
                    return Map.entry(rs.getLong("progress_id"),
                            new UserRef(rs.getLong("id"), rs.getString("name"), role));
                }, ids.toArray());
        Map<Long, List<UserRef>> byProgress = new LinkedHashMap<>();
        for (Map.Entry<Long, UserRef> pair : pairs) {
            byProgress.computeIfAbsent(pair.getKey(), k -> new ArrayList<>()).add(pair.getValue());
        }
        List<ProgressRow> filled = new ArrayList<>(rows.size());
        for (ProgressRow row : rows) {
            filled.add(new ProgressRow(row.id(), row.content(), row.author(),
                    byProgress.getOrDefault(row.id(), List.of()), row.mentionedMe(), row.createdAt()));
        }
        return filled;
    }

    private static ProgressRow map(java.sql.ResultSet rs) throws java.sql.SQLException {
        Role role = null;
        try {
            role = Role.of(rs.getString("author_role"));
        } catch (RuntimeException ignored) {
            // 展示容错
        }
        Timestamp createdAt = rs.getTimestamp("created_at");
        LocalDateTime created = createdAt == null ? null : createdAt.toLocalDateTime();
        return new ProgressRow(
                rs.getLong("id"),
                rs.getString("content"),
                new UserRef(rs.getLong("author_id"), rs.getString("author_name"), role),
                List.of(),
                false,
                created);
    }
}
