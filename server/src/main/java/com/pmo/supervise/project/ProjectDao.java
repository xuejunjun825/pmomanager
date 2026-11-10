package com.pmo.supervise.project;

import com.pmo.supervise.project.dto.ProjectView;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.List;
import java.util.Optional;

/**
 * projects 的原生 SQL 读写（TECHNICAL §3.1）。
 *
 * <p>沿用 {@code SourceTypeDao} 的分层：计数与加锁集中在这一层，Service 不拼 SQL。
 * 上限判断要「读与写在同一事务」，所以 {@link #lockAllIds()} 与 {@link #insert} 由 Service 在同一事务里串起来。
 */
@Repository
public class ProjectDao {

    /** 项目行的完整读模型（含 id 与启用状态），Service 的校验都基于它。 */
    public record ProjectRow(long id, String code, String name, int sort, boolean enabled) {
    }

    private static final String COLUMNS = """
            SELECT p.id, p.code, p.name, p.sort_no, p.enabled
            FROM projects p
            """;

    private static final RowMapper<ProjectRow> ROW = (rs, rowNum) -> new ProjectRow(
            rs.getLong("id"),
            rs.getString("code"),
            rs.getString("name"),
            rs.getInt("sort_no"),
            rs.getBoolean("enabled"));

    private final JdbcTemplate jdbc;

    public ProjectDao(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 配置页列表：含停用项与每项被多少条任务引用（删除按钮据此决定是否渲染）。 */
    public List<ProjectView> listWithUsage() {
        return jdbc.query("""
                SELECT p.id, p.code, p.name, p.sort_no, p.enabled,
                       (SELECT COUNT(*) FROM tasks t WHERE t.source_project_id = p.id) AS usage_count
                FROM projects p
                ORDER BY p.sort_no, p.id
                """, (rs, rowNum) -> new ProjectView(
                rs.getLong("id"),
                rs.getString("code"),
                rs.getString("name"),
                rs.getInt("sort_no"),
                rs.getBoolean("enabled"),
                rs.getLong("usage_count")));
    }

    /** meta 与校验用的全量行（含停用），排序与配置页一致。 */
    public List<ProjectRow> listAll() {
        return jdbc.query(COLUMNS + " ORDER BY p.sort_no, p.id", ROW);
    }

    public Optional<ProjectRow> findById(long id) {
        List<ProjectRow> rows = jdbc.query(COLUMNS + " WHERE p.id = ?", ROW, id);
        return rows.stream().findFirst();
    }

    /**
     * 锁住全部项目行后返回 id：200 上限的判断与随后的 INSERT 落在同一事务，
     * 两个并发新增不会都读到 199。锁定全量（含停用）是因为上限口径含停用。
     */
    public List<Long> lockAllIds() {
        return jdbc.query("SELECT id FROM projects FOR UPDATE", (rs, rowNum) -> rs.getLong(1));
    }

    public int nextSort() {
        Integer max = jdbc.queryForObject("SELECT COALESCE(MAX(sort_no), -1) FROM projects", Integer.class);
        return (max == null ? 0 : max) + 1;
    }

    /** 含停用查重：两条同名（仅大小写不同也不行）只放一条，库层 uk_project_name 兜并发。 */
    public boolean nameExists(String name, Long excludeId) {
        List<Long> rows = excludeId == null
                ? jdbc.query("SELECT id FROM projects WHERE name = ?", (rs, i) -> rs.getLong(1), name)
                : jdbc.query("SELECT id FROM projects WHERE name = ? AND id <> ?",
                        (rs, i) -> rs.getLong(1), name, excludeId);
        return !rows.isEmpty();
    }

    /** 编码两阶段生成的第一步：先用占位码插入，拿到自增 id 再回写 P+5 位。 */
    public long insert(String name, int sort, boolean enabled, String placeholderCode) {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement ps = connection.prepareStatement("""
                    INSERT INTO projects (code, name, sort_no, enabled)
                    VALUES (?, ?, ?, ?)
                    """, Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, placeholderCode);
            ps.setString(2, name);
            ps.setInt(3, sort);
            ps.setInt(4, enabled ? 1 : 0);
            return ps;
        }, keys);
        Number key = keys.getKey();
        if (key == null) {
            throw new IllegalStateException("projects 未返回自增主键");
        }
        return key.longValue();
    }

    public void updateCode(long id, String code) {
        jdbc.update("UPDATE projects SET code = ? WHERE id = ?", code, id);
    }

    public void update(long id, String name, int sort, boolean enabled) {
        jdbc.update("UPDATE projects SET name = ?, sort_no = ?, enabled = ? WHERE id = ?",
                name, sort, enabled ? 1 : 0, id);
    }

    public void updateEnabled(long id, boolean enabled) {
        jdbc.update("UPDATE projects SET enabled = ? WHERE id = ?", enabled ? 1 : 0, id);
    }

    public void delete(long id) {
        jdbc.update("DELETE FROM projects WHERE id = ?", id);
    }

    public long countTasks(long id) {
        Long count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM tasks WHERE source_project_id = ?", Long.class, id);
        return count == null ? 0 : count;
    }
}
