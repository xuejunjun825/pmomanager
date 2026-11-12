package com.pmo.supervise.task;

import com.pmo.supervise.common.BusinessException;
import com.pmo.supervise.common.TaskStatus;
import com.pmo.supervise.project.ProjectFilterSql;
import com.pmo.supervise.source.SourceTypeService;
import com.pmo.supervise.task.dto.TaskFilter;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 把筛选条件编译成带命名参数的 WHERE / ORDER BY（TECHNICAL §4.4）。
 * 四个读页面共享同一个 WHERE 生成器，是 AC-17 跨页一致的结构性保证；
 * 参数全部走命名绑定，关键字不拼进 SQL。
 *
 * <p>来源编码不再是枚举：校验统一交给 SourceTypeService.requireKnown，
 * 未知编码是 400 而不是库层 500；矩阵与任务池因此天然同集合（AC-5）。
 */
@Component
public class TaskQueryBuilder {

    private final SourceTypeService sourceTypes;
    private final ProjectFilterSql projectFilter;
    private final ThemeFilterSql themeFilter;

    /** 单测便捷构造：主题谓词无依赖，直接 new 一个真实实例即可。 */
    public TaskQueryBuilder(SourceTypeService sourceTypes, ProjectFilterSql projectFilter) {
        this(sourceTypes, projectFilter, new ThemeFilterSql());
    }

    @org.springframework.beans.factory.annotation.Autowired
    public TaskQueryBuilder(SourceTypeService sourceTypes, ProjectFilterSql projectFilter,
                            ThemeFilterSql themeFilter) {
        this.sourceTypes = sourceTypes;
        this.projectFilter = projectFilter;
        this.themeFilter = themeFilter;
    }

    public TaskQuery build(TaskFilter filter, LocalDate today) {
        TaskFilter f = filter == null ? new TaskFilter() : filter;
        Map<String, Object> params = new LinkedHashMap<>();
        LocalDate effectiveToday = today;
        params.put("today", effectiveToday);
        params.put("tomorrow", effectiveToday.plusDays(1));

        StringBuilder where = new StringBuilder(" WHERE 1 = 1");

        if (hasText(f.getStatus())) {
            TaskStatus status = parseStatus(f.getStatus());
            where.append(" AND t.status = :status");
            params.put("status", status.name());
        }
        if (f.getOwnerId() != null) {
            where.append(" AND t.owner_id = :ownerId");
            params.put("ownerId", f.getOwnerId());
        }
        if (hasText(f.getSourceType())) {
            // 停用来源仍可筛选历史任务，所以这里只校验存在，不看 enabled。
            where.append(" AND t.source_type = :sourceType");
            params.put("sourceType", sourceTypes.requireKnown(f.getSourceType().trim()).code());
        }
        // 项目谓词只有一份：任务池 / 看板 / 矩阵都从这里走（AC-5「同源」的结构保证）。
        projectFilter.append(where, params, f.getProject());
        // 主题谓词同样只有一份：任务池 / 看板 / 矩阵 / 甘特都从这里走（AC-13「同源」）。
        themeFilter.append(where, params, f.getTheme());
        if (f.getMeetingId() != null) {
            where.append(" AND t.source_meeting_id = :meetingId");
            params.put("meetingId", f.getMeetingId());
        }
        if (f.getOverdue() != null) {
            where.append(f.getOverdue() ? " AND " + TaskSql.OVERDUE : " AND NOT " + TaskSql.OVERDUE);
        }
        if (hasText(f.getKeyword())) {
            where.append(" AND (t.title LIKE :keyword OR u.name LIKE :keyword)");
            params.put("keyword", "%" + f.getKeyword().trim() + "%");
        }

        return new TaskQuery(where.toString(), Collections.unmodifiableMap(params), orderBy(f.getSort()));
    }

    private String orderBy(String sort) {
        if (!hasText(sort)) {
            return """
                    ORDER BY (CASE WHEN t.status IN ('DONE','CANCELLED') THEN 1 ELSE 0 END) ASC,
                             (CASE WHEN t.status NOT IN ('DONE','CANCELLED') THEN t.due_date END) ASC,
                             (CASE WHEN t.status IN ('DONE','CANCELLED') THEN t.completed_at END) DESC,
                             t.id DESC""";
        }
        return switch (sort) {
            case "due_asc" -> """
                    ORDER BY (CASE WHEN t.status IN ('DONE','CANCELLED') THEN 1 ELSE 0 END) ASC,
                             t.due_date ASC, t.id DESC""";
            case "updated_desc" -> "ORDER BY t.updated_at DESC, t.id DESC";
            default -> throw BusinessException.validation("sort", "排序方式不合法");
        };
    }

    private TaskStatus parseStatus(String value) {
        try {
            return TaskStatus.valueOf(value.trim().toUpperCase());
        } catch (IllegalArgumentException ex) {
            throw BusinessException.validation("status", "任务状态不合法");
        }
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    public record TaskQuery(String where, Map<String, Object> params, String orderBy) {
    }
}
