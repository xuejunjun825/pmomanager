package com.pmo.supervise.meta;

import com.pmo.supervise.common.Actor;
import com.pmo.supervise.common.Priority;
import com.pmo.supervise.common.Role;
import com.pmo.supervise.common.TaskStatus;
import com.pmo.supervise.common.UserStatus;
import com.pmo.supervise.meta.dto.MetaOptions;
import com.pmo.supervise.meta.dto.MetaOptions.MeetingOption;
import com.pmo.supervise.meta.dto.MetaOptions.Option;
import com.pmo.supervise.meta.dto.MetaOptions.OwnerOption;
import com.pmo.supervise.project.ProjectService;
import com.pmo.supervise.source.SourceTypeService;
import com.pmo.supervise.source.dto.SourceTypeOption;
import com.pmo.supervise.theme.ThemeService;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Arrays;
import java.util.List;

/**
 * 下拉元数据（TECHNICAL §5.5）：owners 对 OWNER 只返回本人，避免任务池/看板筛选暴露他人；
 * meetings 对 OWNER 只返回自己任务涉及的会议（会议接口本身 admin-only，不能借 meta 绕过）。
 */
@Service
public class MetaService {

    private static final String OWNER_COLUMNS = "SELECT id, name, role, status FROM users";

    private final JdbcTemplate jdbc;
    private final SourceTypeService sourceTypes;
    private final ProjectService projects;
    private final ThemeService themes;

    public MetaService(JdbcTemplate jdbc, SourceTypeService sourceTypes, ProjectService projects,
                       ThemeService themes) {
        this.jdbc = jdbc;
        this.sourceTypes = sourceTypes;
        this.projects = projects;
        this.themes = themes;
    }

    @Transactional(readOnly = true)
    public MetaOptions load(Actor actor) {
        boolean globalReader = actor.isAdmin() || actor.isExec();
        List<OwnerOption> owners = globalReader
                ? jdbc.query(OWNER_COLUMNS + " WHERE status = 'ACTIVE' ORDER BY name, id", (rs, rowNum) ->
                        new OwnerOption(rs.getLong("id"), rs.getString("name"),
                                Role.valueOf(rs.getString("role")),
                                UserStatus.valueOf(rs.getString("status"))))
                : jdbc.query(OWNER_COLUMNS + " WHERE id = ?", (rs, rowNum) ->
                        new OwnerOption(rs.getLong("id"), rs.getString("name"),
                                Role.valueOf(rs.getString("role")),
                                UserStatus.valueOf(rs.getString("status"))), actor.id());

        List<MeetingOption> meetings = globalReader
                ? jdbc.query("""
                        SELECT id, title, meeting_date FROM meetings
                        ORDER BY meeting_date DESC, id DESC
                        """, (rs, rowNum) -> new MeetingOption(
                        rs.getLong("id"), rs.getString("title"),
                        rs.getObject("meeting_date", java.time.LocalDate.class)))
                : jdbc.query("""
                        SELECT DISTINCT m.id, m.title, m.meeting_date
                        FROM meetings m
                        JOIN tasks t ON t.source_meeting_id = m.id
                        WHERE t.owner_id = ?
                        ORDER BY m.meeting_date DESC, m.id DESC
                        """, (rs, rowNum) -> new MeetingOption(
                        rs.getLong("id"), rs.getString("title"),
                        rs.getObject("meeting_date", java.time.LocalDate.class)), actor.id());

        return new MetaOptions(
                owners,
                meetings,
                // 停用来源也要出现在筛选里：历史任务仍挂着它们（AC-5）。
                sourceTypes.options(),
                // 项目不是权限维度：全员同一份（含停用，前端投影决定后缀与可选项，AC-8）。
                projects.options(),
                // 主题同项目：全员可读、含停用；选项不带 usageCount，避免 meta 做 count 子查询（AC-8）。
                themes.options(),
                Arrays.stream(TaskStatus.values()).map(v -> new Option<>(v, v.label())).toList(),
                Arrays.stream(Priority.values()).map(v -> new Option<>(v, v.label())).toList());
    }
}
