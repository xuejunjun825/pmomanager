package com.pmo.supervise.meeting;

import com.pmo.supervise.common.Actor;
import com.pmo.supervise.common.ApiError;
import com.pmo.supervise.common.BusinessException;
import com.pmo.supervise.common.ErrorCode;
import com.pmo.supervise.common.PageResponse;
import com.pmo.supervise.common.TaskStatus;
import com.pmo.supervise.meeting.dto.DecisionItemRequest;
import com.pmo.supervise.meeting.dto.DecisionItemRow;
import com.pmo.supervise.meeting.dto.MeetingDetail;
import com.pmo.supervise.meeting.dto.MeetingRequest;
import com.pmo.supervise.meeting.dto.MeetingRow;
import com.pmo.supervise.task.OverdueSpec;
import com.pmo.supervise.task.TaskAccessGuard;
import com.pmo.supervise.task.TaskSql;
import com.pmo.supervise.task.dto.UserRef;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 会议与决议事项（TECHNICAL §4.4 / §5.3）。
 * 会议只归档不删除；归档后禁止编辑与增删决议项（409 MEETING_ARCHIVED）；
 * 决议项一旦转为任务即锁定（409 DECISION_ITEM_LOCKED），保证来源快照稳定。
 * 闭环口径 taskCount / unfinishedCount 与任务状态联动，未完成谓词复用 TaskSql.NOT_FINISHED。
 */
@Service
public class MeetingService {

    private static final String MEETING_COLUMNS = """
            SELECT m.id, m.title, m.meeting_date, m.host, m.archived,
                   m.note, m.attendees,
                   COALESCE(c.task_count, 0)       AS task_count,
                   COALESCE(c.unfinished_count, 0) AS unfinished_count
            FROM meetings m
            LEFT JOIN (
                SELECT t.source_meeting_id AS meeting_id,
                       COUNT(*) AS task_count,
                       SUM(CASE WHEN %s THEN 1 ELSE 0 END) AS unfinished_count
                FROM tasks t
                WHERE t.source_meeting_id IS NOT NULL
                GROUP BY t.source_meeting_id
            ) c ON c.meeting_id = m.id
            """.formatted(TaskSql.NOT_FINISHED);

    private static final String ITEM_SELECT = """
            SELECT d.id, d.description, d.owner_id, u.name AS owner_name, u.role AS owner_role,
                   d.due_date, d.delivery_note, d.sort_no,
                   t.id AS task_id, t.status AS task_status, t.due_date AS task_due_date
            FROM decision_items d
            JOIN users u ON u.id = d.owner_id
            LEFT JOIN tasks t ON t.source_decision_item_id = d.id
            """;

    private final MeetingRepository meetingRepository;
    private final DecisionItemRepository itemRepository;
    private final TaskAccessGuard guard;
    private final JdbcTemplate jdbc;
    private final NamedParameterJdbcTemplate namedJdbc;

    public MeetingService(MeetingRepository meetingRepository, DecisionItemRepository itemRepository,
                          TaskAccessGuard guard, JdbcTemplate jdbc, NamedParameterJdbcTemplate namedJdbc) {
        this.meetingRepository = meetingRepository;
        this.itemRepository = itemRepository;
        this.guard = guard;
        this.jdbc = jdbc;
        this.namedJdbc = namedJdbc;
    }

    // ---------------------------------------------------------------- 会议

    @Transactional(readOnly = true)
    public PageResponse<MeetingRow> list(boolean archived, String keyword, int page, int size, Actor actor) {
        guard.requireAdmin(actor);
        int safePage = Math.max(page, 1);
        int safeSize = Math.min(Math.max(size, 1), 100);
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("archived", archived ? 1 : 0);

        StringBuilder where = new StringBuilder(" WHERE m.archived = :archived");
        if (keyword != null && !keyword.isBlank()) {
            where.append(" AND m.title LIKE :keyword");
            params.addValue("keyword", "%" + keyword.trim() + "%");
        }

        Long total = namedJdbc.queryForObject("SELECT COUNT(*) FROM meetings m" + where,
                params, Long.class);
        params.addValue("limit", safeSize).addValue("offset", (safePage - 1) * safeSize);
        List<MeetingRow> rows = namedJdbc.query(
                MEETING_COLUMNS + where + " ORDER BY m.meeting_date DESC, m.id DESC LIMIT :limit OFFSET :offset",
                params, (rs, rowNum) -> mapMeeting(rs));
        return PageResponse.of(rows, total == null ? 0 : total, safePage, safeSize);
    }

    @Transactional
    public MeetingDetail create(Actor actor, MeetingRequest req, LocalDate today) {
        guard.requireAdmin(actor);

        Meeting meeting = new Meeting();
        apply(meeting, req);
        meeting.setCreatedBy(actor.id());
        meeting = meetingRepository.saveAndFlush(meeting);
        return get(meeting.getId(), today);
    }

    @Transactional
    public MeetingDetail update(long id, Actor actor, MeetingRequest req, LocalDate today) {
        guard.requireAdmin(actor);
        Meeting meeting = require(id);
        if (isArchived(id)) {
            throw new BusinessException(ErrorCode.MEETING_ARCHIVED);
        }
        apply(meeting, req);
        meetingRepository.saveAndFlush(meeting);
        return get(id, today);
    }

    /** 只归档不删除（AC-5）：不提供物理删除接口，重复归档幂等。 */
    @Transactional
    public MeetingDetail archive(long id, Actor actor, LocalDate today) {
        guard.requireAdmin(actor);
        require(id);
        jdbc.update("UPDATE meetings SET archived = 1 WHERE id = ? AND archived = 0", id);
        return get(id, today);
    }

    @Transactional(readOnly = true)
    public MeetingDetail get(long id, Actor actor, LocalDate today) {
        guard.requireAdmin(actor);
        return get(id, today);
    }

    @Transactional(readOnly = true)
    public MeetingDetail get(long id, LocalDate today) {
        MapSqlParameterSource params = new MapSqlParameterSource("id", id);
        List<MeetingDetailHeader> headers = namedJdbc.query(MEETING_COLUMNS + " WHERE m.id = :id",
                params, (rs, rowNum) -> new MeetingDetailHeader(
                        mapMeeting(rs),
                        rs.getString("note"),
                        rs.getString("attendees")));
        if (headers.isEmpty()) {
            throw BusinessException.notFound("会议不存在");
        }
        MeetingDetailHeader header = headers.get(0);
        MeetingRow row = header.row();
        return new MeetingDetail(
                row.id(), row.title(), row.meetingDate(), row.host(), row.archived(),
                resolveAttendees(header.attendeeIds()), header.note(),
                listItems(id, today),
                row.taskCount(), row.unfinishedCount(), row.closed());
    }

    // ---------------------------------------------------------------- 决议事项

    @Transactional
    public DecisionItemRow addItem(long meetingId, Actor actor, DecisionItemRequest req, LocalDate today) {
        guard.requireAdmin(actor);
        require(meetingId);
        if (isArchived(meetingId)) {
            throw new BusinessException(ErrorCode.MEETING_ARCHIVED);
        }
        int sortNo = itemRepository.nextSortNo(meetingId);
        List<ApiError.FieldError> errors = validateItem(req, sortNo);
        throwIfInvalid(errors);

        DecisionItem item = new DecisionItem();
        item.setMeetingId(meetingId);
        apply(item, req);
        item.setSortNo(sortNo);
        item = itemRepository.saveAndFlush(item);
        return loadItem(item.getId(), today);
    }

    @Transactional
    public DecisionItemRow updateItem(long itemId, Actor actor, DecisionItemRequest req, LocalDate today) {
        guard.requireAdmin(actor);
        DecisionItem item = requireItemForUpdate(itemId);
        if (convertedTaskId(itemId).isPresent()) {
            throw new BusinessException(ErrorCode.DECISION_ITEM_LOCKED);
        }
        if (isArchived(item.getMeetingId())) {
            throw new BusinessException(ErrorCode.MEETING_ARCHIVED);
        }
        int index = itemRepository.positionOf(item.getMeetingId(), item.getSortNo(), itemId);
        List<ApiError.FieldError> errors = validateItem(req, index);
        throwIfInvalid(errors);

        apply(item, req);
        itemRepository.saveAndFlush(item);
        return loadItem(itemId, today);
    }

    @Transactional
    public void deleteItem(long itemId, Actor actor) {
        guard.requireAdmin(actor);
        DecisionItem item = requireItemForUpdate(itemId);
        if (convertedTaskId(itemId).isPresent()) {
            throw new BusinessException(ErrorCode.DECISION_ITEM_LOCKED);
        }
        if (isArchived(item.getMeetingId())) {
            throw new BusinessException(ErrorCode.MEETING_ARCHIVED);
        }
        itemRepository.delete(item);
    }

    @Transactional(readOnly = true)
    public Meeting require(long id) {
        return meetingRepository.findById(id).orElseThrow(() -> BusinessException.notFound("会议不存在"));
    }

    /** 供 DecisionConversionService 使用：已转任务则返回任务 id。 */
    @Transactional(readOnly = true)
    public java.util.Optional<Long> convertedTaskId(long itemId) {
        List<Long> ids = jdbc.query("SELECT id FROM tasks WHERE source_decision_item_id = ?",
                (rs, rowNum) -> rs.getLong(1), itemId);
        return ids.isEmpty() ? java.util.Optional.empty() : java.util.Optional.of(ids.get(0));
    }

    @Transactional(readOnly = true)
    public boolean isArchived(long meetingId) {
        List<Integer> rows = jdbc.query("SELECT archived FROM meetings WHERE id = ?",
                (rs, rowNum) -> rs.getInt(1), meetingId);
        if (rows.isEmpty()) {
            throw BusinessException.notFound("会议不存在");
        }
        return rows.get(0) != 0;
    }

    // ---------------------------------------------------------------- 内部

    private void apply(Meeting meeting, MeetingRequest req) {
        meeting.setTitle(req.title().trim());
        meeting.setMeetingDate(req.meetingDate());
        meeting.setHost(req.host().trim());
        meeting.setAttendees(joinAttendees(validateAttendees(req.attendeeIds())));
        meeting.setNote(req.note() == null || req.note().isBlank() ? null : req.note().trim());
    }

    private void apply(DecisionItem item, DecisionItemRequest req) {
        item.setDescription(req.description().trim());
        item.setOwnerId(req.ownerId());
        item.setDueDate(req.dueDate());
        item.setDeliveryNote(req.deliveryNote() == null || req.deliveryNote().isBlank()
                ? null : req.deliveryNote().trim());
    }

    /** 参会人只落 ACTIVE 用户；未知/停用统一以 422 attendeeIds 回传。 */
    private List<Long> validateAttendees(List<Long> attendeeIds) {
        if (attendeeIds == null || attendeeIds.isEmpty()) {
            return List.of();
        }
        List<Long> ids = attendeeIds.stream().filter(Objects::nonNull).distinct().toList();
        if (ids.isEmpty()) {
            return List.of();
        }
        Map<Long, String> statuses = new LinkedHashMap<>();
        namedJdbc.query("SELECT id, status FROM users WHERE id IN (:ids)",
                new MapSqlParameterSource("ids", ids),
                rs -> {
                    statuses.put(rs.getLong("id"), rs.getString("status"));
                });
        for (Long id : ids) {
            String status = statuses.get(id);
            if (status == null) {
                throw BusinessException.validation("attendeeIds", "参会人不存在（id=" + id + "）");
            }
            if (!"ACTIVE".equals(status)) {
                throw BusinessException.validation("attendeeIds", "参会人已停用（id=" + id + "）");
            }
        }
        return ids;
    }

    private String joinAttendees(List<Long> ids) {
        if (ids.isEmpty()) {
            return null;
        }
        return ids.stream().map(String::valueOf).collect(java.util.stream.Collectors.joining(","));
    }

    private List<UserRef> resolveAttendees(String csv) {
        if (csv == null || csv.isBlank()) {
            return List.of();
        }
        List<Long> ids = new ArrayList<>();
        for (String token : csv.split(",")) {
            String trimmed = token.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            try {
                ids.add(Long.valueOf(trimmed));
            } catch (NumberFormatException ignored) {
                // 兼容历史自由文本：非 id 的参会人不进 UserRef 列表
            }
        }
        if (ids.isEmpty()) {
            return List.of();
        }
        Map<Long, UserRef> byId = new LinkedHashMap<>();
        namedJdbc.query("SELECT id, name, role FROM users WHERE id IN (:ids)",
                new MapSqlParameterSource("ids", ids),
                rs -> {
                    byId.put(rs.getLong("id"),
                            new UserRef(rs.getLong("id"), rs.getString("name"),
                                    com.pmo.supervise.common.Role.of(rs.getString("role"))));
                });
        return ids.stream().map(byId::get).filter(Objects::nonNull).toList();
    }

    private List<DecisionItemRow> listItems(long meetingId, LocalDate today) {
        return namedJdbc.query(ITEM_SELECT + " WHERE d.meeting_id = :meetingId ORDER BY d.sort_no, d.id",
                new MapSqlParameterSource("meetingId", meetingId),
                (rs, rowNum) -> mapItem(rs, today));
    }

    private DecisionItemRow loadItem(long itemId, LocalDate today) {
        List<DecisionItemRow> rows = namedJdbc.query(ITEM_SELECT + " WHERE d.id = :id",
                new MapSqlParameterSource("id", itemId),
                (rs, rowNum) -> mapItem(rs, today));
        if (rows.isEmpty()) {
            throw BusinessException.notFound("决议事项不存在");
        }
        return rows.get(0);
    }

    private DecisionItem requireItemForUpdate(long itemId) {
        return itemRepository.findByIdForUpdate(itemId)
                .orElseThrow(() -> BusinessException.notFound("决议事项不存在"));
    }

    /** 逐项校验：缺失项以 fieldErrors 一次回传，index = 该行在当前排序中的位置（0 基）。 */
    private List<ApiError.FieldError> validateItem(DecisionItemRequest req, int index) {
        List<ApiError.FieldError> errors = new ArrayList<>();
        String description = req.description() == null ? "" : req.description().trim();
        if (description.isEmpty()) {
            errors.add(new ApiError.FieldError("description", "请填写事项描述", index));
        } else if (description.length() > 100) {
            errors.add(new ApiError.FieldError("description", "事项描述不能超过 100 字", index));
        }
        if (req.ownerId() == null) {
            errors.add(new ApiError.FieldError("ownerId", "请选择负责人", index));
        } else {
            List<String> status = jdbc.query("SELECT status FROM users WHERE id = ?",
                    (rs, rowNum) -> rs.getString(1), req.ownerId());
            if (status.isEmpty()) {
                errors.add(new ApiError.FieldError("ownerId", "负责人不存在", index));
            } else if (!"ACTIVE".equals(status.get(0))) {
                errors.add(new ApiError.FieldError("ownerId", "负责人已停用", index));
            }
        }
        if (req.dueDate() == null) {
            errors.add(new ApiError.FieldError("dueDate", "请选择截止日期", index));
        }
        if (req.deliveryNote() != null && req.deliveryNote().length() > 500) {
            errors.add(new ApiError.FieldError("deliveryNote", "交付说明不能超过 500 字", index));
        }
        return errors;
    }

    private void throwIfInvalid(List<ApiError.FieldError> errors) {
        if (!errors.isEmpty()) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "决议事项校验失败", errors, Map.of());
        }
    }

    private static MeetingRow mapMeeting(ResultSet rs) throws SQLException {
        long taskCount = rs.getLong("task_count");
        long unfinished = rs.getLong("unfinished_count");
        return new MeetingRow(
                rs.getLong("id"),
                rs.getString("title"),
                rs.getObject("meeting_date", LocalDate.class),
                rs.getString("host"),
                rs.getInt("archived") != 0,
                taskCount,
                unfinished,
                taskCount > 0 && unfinished == 0);
    }

    private static DecisionItemRow mapItem(ResultSet rs, LocalDate today) throws SQLException {
        TaskStatus taskStatus = rs.getString("task_status") == null
                ? null : TaskStatus.of(rs.getString("task_status"));
        Long taskId = rs.getObject("task_id", Long.class);
        LocalDate itemDue = rs.getObject("due_date", LocalDate.class);
        LocalDate taskDue = rs.getObject("task_due_date", LocalDate.class);

        boolean overdue;
        int overdueDays;
        if (taskId != null) {
            overdue = OverdueSpec.isOverdue(taskStatus, taskDue, today);
            overdueDays = OverdueSpec.overdueDays(taskStatus, taskDue, today);
        } else {
            overdue = OverdueSpec.isOverdue(TaskStatus.PENDING, itemDue, today);
            overdueDays = OverdueSpec.overdueDays(TaskStatus.PENDING, itemDue, today);
        }

        return new DecisionItemRow(
                rs.getLong("id"),
                rs.getString("description"),
                new UserRef(rs.getLong("owner_id"), rs.getString("owner_name"),
                        com.pmo.supervise.common.Role.of(rs.getString("owner_role"))),
                itemDue,
                rs.getString("delivery_note"),
                rs.getInt("sort_no"),
                taskId != null,
                taskId,
                taskStatus,
                overdue,
                overdueDays);
    }

    private record MeetingDetailHeader(MeetingRow row, String note, String attendeeIds) {
    }
}
