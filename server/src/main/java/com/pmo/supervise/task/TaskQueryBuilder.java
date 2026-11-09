package com.pmo.supervise.task;

import com.pmo.supervise.common.BusinessException;
import com.pmo.supervise.common.SourceType;
import com.pmo.supervise.common.TaskStatus;
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
 */
@Component
public class TaskQueryBuilder {

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
            SourceType sourceType = parseSourceType(f.getSourceType());
            where.append(" AND t.source_type = :sourceType");
            params.put("sourceType", sourceType.name());
        }
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

    private SourceType parseSourceType(String value) {
        try {
            return SourceType.valueOf(value.trim().toUpperCase());
        } catch (IllegalArgumentException ex) {
            throw BusinessException.validation("sourceType", "来源类型不合法");
        }
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    public record TaskQuery(String where, Map<String, Object> params, String orderBy) {
    }
}
