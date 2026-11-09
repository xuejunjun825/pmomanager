package com.pmo.supervise.task;

import com.pmo.supervise.common.Actor;
import com.pmo.supervise.common.BusinessException;
import com.pmo.supervise.common.ErrorCode;
import com.pmo.supervise.common.Priority;
import com.pmo.supervise.common.Role;
import com.pmo.supervise.common.SourceType;
import com.pmo.supervise.common.TaskStatus;
import com.pmo.supervise.task.dto.CreateTaskRequest;
import com.pmo.supervise.task.dto.TaskDetailRow;
import com.pmo.supervise.task.dto.TaskEventRow;
import com.pmo.supervise.task.dto.TaskPermissions;
import com.pmo.supervise.task.dto.UpdateTaskRequest;
import com.pmo.supervise.task.dto.UserRef;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 任务创建 / 编辑 / 详情（TECHNICAL §4.5）。创建时派发人取当前登录人，
 * 来源字段不可修改；改人/改期走 TaskCommandService 的 reassign/reschedule 留痕。
 */
@Service
public class TaskService {

    private final TaskRepository taskRepository;
    private final TaskQueryDao queryDao;
    private final TaskAccessGuard guard;
    private final JdbcTemplate jdbc;

    public TaskService(TaskRepository taskRepository, TaskQueryDao queryDao,
                       TaskAccessGuard guard, JdbcTemplate jdbc) {
        this.taskRepository = taskRepository;
        this.queryDao = queryDao;
        this.guard = guard;
        this.jdbc = jdbc;
    }

    @Transactional
    public TaskDetailRow create(Actor actor, CreateTaskRequest req, LocalDate today) {
        guard.requireAdmin(actor);
        validateOwner(req.ownerId());
        validateSource(req);

        Task task = new Task();
        task.setTitle(req.title().trim());
        task.setOwnerId(req.ownerId());
        task.setDueDate(req.dueDate());
        task.setSourceType(req.sourceType());
        task.setSourceMeetingId(req.sourceMeetingId());
        task.setSourceDecisionItemId(req.sourceDecisionItemId());
        task.setSourceNote(req.sourceNote() == null || req.sourceNote().isBlank() ? null : req.sourceNote().trim());
        task.setStatus(TaskStatus.PENDING);
        task.setDispatcherId(actor.id());
        task.setPriority(req.priority() == null ? Priority.MEDIUM : req.priority());
        task = taskRepository.saveAndFlush(task);

        return load(task.getId(), actor, today);
    }

    @Transactional
    public TaskDetailRow update(long id, Actor actor, UpdateTaskRequest req, LocalDate today) {
        Task task = require(id);
        guard.requireAdmin(actor);
        if (req.hasSourceFields()) {
            throw new BusinessException(ErrorCode.TASK_SOURCE_IMMUTABLE, "任务来源不可修改");
        }
        if (req.ownerId() != null || req.dueDate() != null) {
            Map<String, String> fields = new LinkedHashMap<>();
            if (req.ownerId() != null) {
                fields.put("ownerId", "请使用改派操作变更负责人");
            }
            if (req.dueDate() != null) {
                fields.put("dueDate", "请使用改期操作变更截止日期");
            }
            throw BusinessException.validation(fields);
        }
        if (req.title() != null) {
            if (req.title().isBlank()) {
                throw BusinessException.validation("title", "任务标题不能为空");
            }
            task.setTitle(req.title().trim());
        }
        if (req.priority() != null) {
            task.setPriority(req.priority());
        }
        taskRepository.saveAndFlush(task);
        return load(id, actor, today);
    }

    @Transactional(readOnly = true)
    public TaskDetailRow get(long id, Actor actor, LocalDate today) {
        Task task = require(id);
        guard.requireView(actor, task);
        return load(id, actor, today);
    }

    public Task require(long id) {
        return taskRepository.findById(id).orElseThrow(() -> BusinessException.notFound("任务不存在"));
    }

    public TaskDetailRow load(long id, Actor actor, LocalDate today) {
        Task task = require(id);
        TaskDetailRow row = queryDao.findDetail(id, today)
                .orElseThrow(() -> BusinessException.notFound("任务不存在"));
        List<TaskEventRow> events = queryDao.events(id);
        return row.with(events, permissions(task, actor));
    }

    /** 详情 permissions 由服务端计算，前端只按此渲染动作（AC-9 / §4.3）。 */
    public TaskPermissions permissions(Task task, Actor actor) {
        boolean unfinished = task.getStatus().isUnfinished();
        boolean owner = task.getOwnerId() == actor.id();
        boolean dispatcher = task.getDispatcherId() == actor.id();
        Map<String, String> reasons = new LinkedHashMap<>();

        boolean canStart = unfinished && task.getStatus() == TaskStatus.PENDING && (owner || actor.isAdmin());
        boolean canSubmit = unfinished
                && (task.getStatus() == TaskStatus.PENDING || task.getStatus() == TaskStatus.IN_PROGRESS)
                && (owner || actor.isAdmin());
        boolean canAccept = task.getStatus() == TaskStatus.PENDING_ACCEPT && (actor.isAdmin() || dispatcher);
        boolean canReject = task.getStatus() == TaskStatus.PENDING_ACCEPT && (actor.isAdmin() || dispatcher);
        boolean canCancel = unfinished && actor.isAdmin();
        boolean canRestore = task.getStatus() == TaskStatus.CANCELLED && actor.isAdmin();
        boolean canForceComplete = unfinished && actor.isAdmin();
        boolean canReassign = unfinished && (actor.isAdmin() || actor.isExec());
        boolean canReschedule = unfinished && (actor.isAdmin() || actor.isExec());
        boolean canUrge = unfinished && (actor.isAdmin() || actor.isExec());

        if (!canStart && task.getStatus() != TaskStatus.PENDING && (owner || actor.isAdmin())) {
            reasons.put("start", "任务已开始或已结束");
        }
        if (!canSubmit && (owner || actor.isAdmin())) {
            reasons.put("submit", task.getStatus().isFinished() ? "任务已结束" : "当前状态不可提交");
        }
        if (!canAccept && (actor.isAdmin() || dispatcher)) {
            reasons.put("accept", task.getStatus().isFinished() ? "任务已结束" : "负责人尚未提交验收");
        }
        if (!canCancel && actor.isAdmin()) {
            reasons.put("cancel", "任务已结束");
        }
        if (!canReassign && (actor.isAdmin() || actor.isExec())) {
            reasons.put("reassign", "任务已结束");
        }
        if (!canReschedule && (actor.isAdmin() || actor.isExec())) {
            reasons.put("reschedule", "任务已结束");
        }
        if (!canUrge && (actor.isAdmin() || actor.isExec())) {
            reasons.put("urge", "任务已结束");
        }

        return new TaskPermissions(canStart, canSubmit, canAccept, canReject, canCancel, canRestore,
                canForceComplete, canReassign, canReschedule, canUrge, reasons);
    }

    private void validateOwner(long ownerId) {
        List<String> rows = jdbc.query(
                "SELECT status FROM users WHERE id = ?", (rs, i) -> rs.getString(1), ownerId);
        if (rows.isEmpty()) {
            throw BusinessException.validation("ownerId", "负责人不存在");
        }
        if (!"ACTIVE".equals(rows.get(0))) {
            throw BusinessException.validation("ownerId", "负责人已停用");
        }
    }

    private void validateSource(CreateTaskRequest req) {
        Map<String, String> fields = new LinkedHashMap<>();
        switch (req.sourceType()) {
            case MEETING -> {
                if (req.sourceMeetingId() == null) {
                    fields.put("sourceMeetingId", "会议来源必须选择会议");
                }
                if (req.sourceDecisionItemId() == null) {
                    fields.put("sourceDecisionItemId", "会议来源必须选择决议项");
                }
                if (req.sourceNote() != null && !req.sourceNote().isBlank()) {
                    fields.put("sourceNote", "会议来源不需要填写说明");
                }
            }
            case PROJECT, DAILY -> {
                if (req.sourceNote() == null || req.sourceNote().isBlank()) {
                    fields.put("sourceNote", "请填写来源说明");
                }
                if (req.sourceMeetingId() != null) {
                    fields.put("sourceMeetingId", "非会议来源不能关联会议");
                }
                if (req.sourceDecisionItemId() != null) {
                    fields.put("sourceDecisionItemId", "非会议来源不能关联决议项");
                }
            }
        }
        if (!fields.isEmpty()) {
            throw BusinessException.validation(fields);
        }
        if (req.sourceType() == SourceType.MEETING) {
            List<Long> meetingIds = jdbc.query(
                    "SELECT id FROM meetings WHERE id = ?", (rs, i) -> rs.getLong(1), req.sourceMeetingId());
            if (meetingIds.isEmpty()) {
                throw BusinessException.validation("sourceMeetingId", "会议不存在");
            }
            List<Long> itemMeetings = jdbc.query(
                    "SELECT meeting_id FROM decision_items WHERE id = ?",
                    (rs, i) -> rs.getLong(1), req.sourceDecisionItemId());
            if (itemMeetings.isEmpty()) {
                throw BusinessException.validation("sourceDecisionItemId", "决议项不存在");
            }
            if (!itemMeetings.get(0).equals(req.sourceMeetingId())) {
                throw BusinessException.validation("sourceDecisionItemId", "决议项不属于所选会议");
            }
            if (taskRepository.existsBySourceDecisionItemId(req.sourceDecisionItemId())) {
                throw BusinessException.validation("sourceDecisionItemId", "该决议项已转任务");
            }
        }
    }

    public static UserRef ref(long id, String name) {
        return new UserRef(id, name, (Role) null);
    }
}
