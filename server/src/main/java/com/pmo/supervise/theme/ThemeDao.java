package com.pmo.supervise.theme;

import com.pmo.supervise.source.SourcePalette;
import com.pmo.supervise.theme.dto.ThemeOption;
import com.pmo.supervise.theme.dto.ThemeView;
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
 * 主题主数据 JDBC 访问（TECHNICAL §3.1）：照 {@code ProjectDao} 的形状，只拼 SQL。
 * 三色在这里由 {@link SourcePalette#of(String)} 一次派生——DAO 之外不再解析 hex，
 * 任务标签（T2 的 ThemeRef）复用同一色板，不会出现第二套主题色（AC-9）。
 */
@Repository
public class ThemeDao {

    public record ThemeRow(long id, String name, String color, int sort, boolean enabled) {
    }

    private static final String COLUMNS = """
            SELECT th.id, th.name, th.color, th.sort_no, th.enabled
            FROM themes th
            """;

    private static final RowMapper<ThemeRow> ROW_MAPPER = (rs, rowNum) -> new ThemeRow(
            rs.getLong("id"),
            rs.getString("name"),
            rs.getString("color"),
            rs.getInt("sort_no"),
            rs.getBoolean("enabled"));

    private final JdbcTemplate jdbc;

    public ThemeDao(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 配置页列表：带引用任务数，按 sort_no, id 排序（AC-8 列表字段）。 */
    public List<ThemeView> listWithUsage() {
        return jdbc.query("""
                SELECT th.id, th.name, th.color, th.sort_no, th.enabled,
                       (SELECT COUNT(*) FROM tasks t WHERE t.theme_id = th.id) AS usage_count
                FROM themes th
                ORDER BY th.sort_no, th.id
                """, (rs, rowNum) -> toView(ROW_MAPPER.mapRow(rs, rowNum), rs.getLong("usage_count")));
    }

    /** 选项源：含停用项（前端置灰），不做 count 子查询（AC-8）。 */
    public List<ThemeRow> listAll() {
        return jdbc.query(COLUMNS + " ORDER BY th.sort_no, th.id", ROW_MAPPER);
    }

    public Optional<ThemeRow> findById(long id) {
        return jdbc.query(COLUMNS + " WHERE th.id = ?", ROW_MAPPER, id).stream().findFirst();
    }

    /** 上限判断与 INSERT 同事务：锁住全部主题行，避免并发新增绕过 100 的硬上限。 */
    public List<Long> lockAllIds() {
        return jdbc.queryForList("SELECT id FROM themes ORDER BY id FOR UPDATE", Long.class);
    }

    public int nextSort() {
        Integer next = jdbc.queryForObject("SELECT COALESCE(MAX(sort_no), -1) + 1 FROM themes", Integer.class);
        return next == null ? 0 : next;
    }

    public boolean nameExists(String name, Long excludeId) {
        String sql = "SELECT COUNT(*) FROM themes WHERE name = ?"
                + (excludeId == null ? "" : " AND id <> ?");
        Integer count = excludeId == null
                ? jdbc.queryForObject(sql, Integer.class, name)
                : jdbc.queryForObject(sql, Integer.class, name, excludeId);
        return count != null && count > 0;
    }

    public long insert(String name, String color, int sort, boolean enabled) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement ps = connection.prepareStatement("""
                    INSERT INTO themes (name, color, sort_no, enabled)
                    VALUES (?, ?, ?, ?)
                    """, Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, name);
            ps.setString(2, color);
            ps.setInt(3, sort);
            ps.setBoolean(4, enabled);
            return ps;
        }, keyHolder);
        Number key = keyHolder.getKey();
        if (key == null) {
            throw new IllegalStateException("主题插入未返回主键");
        }
        return key.longValue();
    }

    public int update(long id, String name, String color, int sort, boolean enabled) {
        return jdbc.update("""
                UPDATE themes SET name = ?, color = ?, sort_no = ?, enabled = ?
                WHERE id = ?
                """, name, color, sort, enabled, id);
    }

    public int updateEnabled(long id, boolean enabled) {
        return jdbc.update("UPDATE themes SET enabled = ? WHERE id = ?", enabled, id);
    }

    public int delete(long id) {
        return jdbc.update("DELETE FROM themes WHERE id = ?", id);
    }

    public long countTasks(long id) {
        Long count = jdbc.queryForObject("SELECT COUNT(*) FROM tasks WHERE theme_id = ?", Long.class, id);
        return count == null ? 0 : count;
    }

    /** 三色由后端派生：接口一次给全，前端不拼色（AC-8 / AC-9）。 */
    public static ThemeView toView(ThemeRow row, long usageCount) {
        SourcePalette.Triple triple = SourcePalette.of(row.color());
        return new ThemeView(row.id(), row.name(), row.color(), triple.soft(), triple.border(),
                row.sort(), row.enabled(), usageCount);
    }

    public static ThemeOption toOption(ThemeRow row) {
        SourcePalette.Triple triple = SourcePalette.of(row.color());
        return new ThemeOption(row.id(), row.name(), row.color(), triple.soft(), triple.border(),
                row.sort(), row.enabled());
    }
}
