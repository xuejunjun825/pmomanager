package com.pmo.supervise.source;

import com.pmo.supervise.source.dto.SourceTypeView;
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
 * source_types 的原生 SQL 读写（TECHNICAL §4.1）。
 * 计数与 join 集中在这一层：上限判断要读与写在同一事务，Service 不拼 SQL。
 */
@Repository
public class SourceTypeDao {

    /** 配置行的完整读模型（含 id 与启用状态），Service 的校验都基于它。 */
    public record SourceTypeConfig(long id, String code, String name, SourceForm form,
                                   String color, int sort, boolean enabled) {
    }

    private static final String COLUMNS = """
            SELECT s.id, s.code, s.name, s.form, s.color, s.sort_no, s.enabled
            FROM source_types s
            """;

    private static final RowMapper<SourceTypeConfig> CONFIG = (rs, rowNum) -> new SourceTypeConfig(
            rs.getLong("id"),
            rs.getString("code"),
            rs.getString("name"),
            SourceForm.of(rs.getString("form")),
            rs.getString("color"),
            rs.getInt("sort_no"),
            rs.getBoolean("enabled"));

    private final JdbcTemplate jdbc;

    public SourceTypeDao(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 配置页列表：含停用项与每项被多少条任务引用（删除按钮据此决定是否渲染）。 */
    public List<SourceTypeView> listWithUsage() {
        return jdbc.query("""
                SELECT s.id, s.code, s.name, s.form, s.color, s.sort_no, s.enabled,
                       (SELECT COUNT(*) FROM tasks t WHERE t.source_type = s.code) AS usage_count
                FROM source_types s
                ORDER BY s.sort_no, s.id
                """, (rs, rowNum) -> toView(CONFIG.mapRow(rs, rowNum), rs.getLong("usage_count")));
    }

    public Optional<SourceTypeConfig> findByCode(String code) {
        if (code == null || code.isBlank()) {
            return Optional.empty();
        }
        List<SourceTypeConfig> rows = jdbc.query(COLUMNS + " WHERE s.code = ?", CONFIG, code.trim());
        return rows.stream().findFirst();
    }

    /**
     * 锁住全部启用行后返回其编码：20 上限的判断与随后的 INSERT 落在同一事务，
     * 两个并发新增不会都读到 19。
     */
    public List<String> lockEnabledCodes() {
        return jdbc.query("SELECT code FROM source_types WHERE enabled = 1 FOR UPDATE",
                (rs, rowNum) -> rs.getString(1));
    }

    public long nextSort() {
        Integer max = jdbc.queryForObject("SELECT COALESCE(MAX(sort_no), -1) FROM source_types", Integer.class);
        return (max == null ? 0 : max) + 1L;
    }

    public boolean nameExists(String name, String excludeCode) {
        List<Long> rows = excludeCode == null
                ? jdbc.query("SELECT id FROM source_types WHERE name = ?", (rs, i) -> rs.getLong(1), name)
                : jdbc.query("SELECT id FROM source_types WHERE name = ? AND code <> ?",
                        (rs, i) -> rs.getLong(1), name, excludeCode);
        return !rows.isEmpty();
    }

    /** 编码两阶段生成的第一步：先用占位码插入，拿到自增 id 再回写 S+5 位。 */
    public long insert(String name, String form, String colorHex, int sort, String placeholderCode) {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement ps = connection.prepareStatement("""
                    INSERT INTO source_types (code, name, form, color, sort_no, enabled)
                    VALUES (?, ?, ?, ?, ?, 1)
                    """, Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, placeholderCode);
            ps.setString(2, name);
            ps.setString(3, form);
            ps.setString(4, colorHex);
            ps.setInt(5, sort);
            return ps;
        }, keys);
        Number key = keys.getKey();
        if (key == null) {
            throw new IllegalStateException("source_types 未返回自增主键");
        }
        return key.longValue();
    }

    public void updateCode(long id, String code) {
        jdbc.update("UPDATE source_types SET code = ? WHERE id = ?", code, id);
    }

    public void update(long id, String name, String form, String colorHex, int sort) {
        jdbc.update("""
                UPDATE source_types SET name = ?, form = ?, color = ?, sort_no = ?
                WHERE id = ?
                """, name, form, colorHex, sort, id);
    }

    public void setEnabled(String code, boolean enabled) {
        jdbc.update("UPDATE source_types SET enabled = ? WHERE code = ?", enabled ? 1 : 0, code);
    }

    public void delete(long id) {
        jdbc.update("DELETE FROM source_types WHERE id = ?", id);
    }

    public long countTasksByCode(String code) {
        Long count = jdbc.queryForObject("SELECT COUNT(*) FROM tasks WHERE source_type = ?", Long.class, code);
        return count == null ? 0 : count;
    }

    /** 形态从 NOTE 改 MEETING 前必须为 0：这些任务没有会议可绑定。 */
    public long countTasksWithoutMeeting(String code) {
        Long count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM tasks WHERE source_type = ? AND source_meeting_id IS NULL",
                Long.class, code);
        return count == null ? 0 : count;
    }

    public static SourceTypeView toView(SourceTypeConfig config, long usageCount) {
        SourcePalette.Triple triple = SourcePalette.of(config.color());
        return new SourceTypeView(
                config.code(),
                config.name(),
                config.form(),
                config.color(),
                triple.soft(),
                triple.border(),
                config.sort(),
                config.enabled(),
                SourceCodes.isLocked(config.code()),
                SourceCodes.isLocked(config.code()),
                usageCount);
    }
}
