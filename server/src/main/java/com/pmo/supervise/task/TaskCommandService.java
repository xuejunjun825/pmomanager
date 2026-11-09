package com.pmo.supervise.task;

import com.pmo.supervise.common.Actor;
import com.pmo.supervise.common.BusinessException;
import com.pmo.supervise.common.ErrorCode;
import com.pmo.supervise.common.NotifyType;
import com.pmo.supervise.common.ShanghaiClock;
import com.pmo.supervise.common.TaskStatus;
import com.pmo.supervise.notify.MessageTemplates;
import com.pmo.supervise.notify.NotificationKeys;
import com.pmo.supervise.notify.NotificationService;
import com.pmo.supervise.notify.dto.CreateNotificationCommand;
import com.pmo.supervise.task.dto.TaskDetailRow;
import com.pmo.supervise.task.dto.TransitionRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 任务状态机（TECHNICAL §4.2）：5 状态 18 迁移，SELECT FOR UPDATE 串行化并发动作，
 * 每次动作写 1 条 task_events（打回/改派/改期同时在事务提交后通知负责人）。
 */
@Service
public class TaskCommandService {

    private final TaskRepository taskRepository;
    private final TaskEventRepository eventRepository;
    private final TaskService taskService;
    private final TaskAccessGuard guard;
    private final NotificationService notificationService;
    private final ShanghaiClock clock;
    private final JdbcTemplate jdbc;

    public TaskCommandService(TaskRepository taskRepository, TaskEventRepository eventRepository,
                              TaskService taskService, TaskAccessGuard guard,
                              NotificationService notificationService, ShanghaiClock clock, JdbcTemplate jdbc) {
        this.taskRepository = taskRepository;
        this.eventRepository = eventRepository;
        this.taskService = taskService;
        this.guard = guard;
        this.notificationService = notificationService;
        this.clock = clock;
        this.jdbc = jdbc;
    }

    @Transactional
    public TaskDetailRow start(long id, Actor actor) {
        Task task = lock(id);
        guard.requireOperate(actor, task);
        return apply(task, actor, TaskAction.START, null, t -> t.setStatus(TaskStatus.IN_PROGRESS));
    }

    @Transactional
    public TaskDetailRow submit(long id, Actor actor, TransitionRequest cmd) {
        Task task = lock(id);
        guard.requireOperate(actor, task);
        String note = requireText(cmd == null ? null : cmd.completionNote(), "completionNote", "请填写完成说明", 10, 500);
        String url = optionalText(cmd == null ? null : cmd.completionUrl(), "completionUrl", 500);
        LocalDateTime now = clock.now();
        return apply(task, actor, TaskAction.SUBMIT, note, t -> {
            t.setStatus(TaskStatus.PENDING_ACCEPT);
            t.setSubmittedAt(now);
            t.setCompletionNote(note);
            t.setCompletionUrl(url);
        });
    }

    @Transactional
    public TaskDetailRow accept(long id, Actor actor) {
        Task task = lock(id);
        guard.requireAccept(actor, task);
        LocalDateTime now = clock.now();
        return apply(task, actor, TaskAction.ACCEPT, null, t -> {
            t.setStatus(TaskStatus.DONE);
            t.setCompletedAt(now);
        });
    }

    @Transactional
    public TaskDetailRow reject(long id, Actor actor, String reason) {
        Task task = lock(id);
        guard.requireAccept(actor, task);
        String text = requireText(reason, "reason", "请填写打回原因", 2, 200);
        TaskDetailRow row = apply(task, actor, TaskAction.REJECT, text, t -> {
            t.setStatus(TaskStatus.IN_PROGRESS);
            t.setSubmittedAt(null);
        });
        notify(task, actor, NotifyType.REJECTED, MessageTemplates.rejectedBody(actor.name(), task.getTitle(), text),
                lastEventId(task.getId()));
        return row;
    }

    @Transactional
    public TaskDetailRow cancel(long id, Actor actor, String reason) {
        Task task = lock(id);
        guard.requireAdmin(actor);
        String text = requireText(reason, "reason", "请填写取消原因", 2, 200);
        LocalDateTime now = clock.now();
        return apply(task, actor, TaskAction.CANCEL, text, t -> {
            t.setStatus(TaskStatus.CANCELLED);
            t.setCancelledAt(now);
        });
    }

    @Transactional
    public TaskDetailRow restore(long id, Actor actor) {
        Task task = lock(id);
        guard.requireAdmin(actor);
        return apply(task, actor, TaskAction.RESTORE, null, t -> {
            t.setStatus(TaskStatus.PENDING);
            t.setCancelledAt(null);
        });
    }

    @Transactional
    public TaskDetailRow forceComplete(long id, Actor actor, String reason) {
        Task task = lock(id);
        guard.requireAdmin(actor);
        String text = requireText(reason, "reason", "请填写代确认原因", 2, 200);
        LocalDateTime now = clock.now();
        return apply(task, actor, TaskAction.FORCE_COMPLETE, text, t -> {
            t.setStatus(TaskStatus.DONE);
            t.setCompletedAt(now);
        });
    }

    @Transactional
    public TaskDetailRow reassign(long id, Actor actor, long ownerId, String reason) {
        Task task = lock(id);
        guard.requireAssign(actor);
        String text = requireText(reason, "reason", "请填写改派原因", 2, 200);
        if (ownerId == task.getOwnerId()) {
            throw BusinessException.validation("ownerId", "新负责人与当前负责人相同");
        }
        requireActiveUser(ownerId, "ownerId");
        String fromName = nameOf(task.getOwnerId());
        String toName = nameOf(ownerId);
        TaskDetailRow row = apply(task, actor, TaskAction.REASSIGN,
                "负责人 " + fromName + " → " + toName + "；" + text,
                t -> t.setOwnerId(ownerId));
        notify(task, actor, NotifyType.REASSIGNED,
                MessageTemplates.reassignedBody(task.getTitle(), fromName, toName, text), lastEventId(task.getId()));
        return row;
    }

    @Transactional
    public TaskDetailRow reschedule(long id, Actor actor, LocalDate dueDate, String reason) {
        Task task = lock(id);
        guard.requireAssign(actor);
        String text = requireText(reason, "reason", "请填写改期原因", 2, 200);
        if (dueDate == null) {
            throw BusinessException.validation("dueDate", "请选择新的截止日期");
        }
        LocalDate oldDate = task.getDueDate();
        if (dueDate.equals(oldDate)) {
            throw BusinessException.validation("dueDate", "新截止日期与当前一致");
        }
        TaskDetailRow row = apply(task, actor, TaskAction.RESCHEDULE,
                "截止 " + oldDate + " → " + dueDate + "；" + text,
                t -> t.setDueDate(dueDate));
        notify(task, actor, NotifyType.RESCHEDULED,
                MessageTemplates.rescheduledBody(task.getTitle(), oldDate, dueDate, text), lastEventId(task.getId()));
        return row;
    }

    private TaskDetailRow apply(Task task, Actor actor, TaskAction action, String reason,
                                java.util.function.Consumer<Task> mutator) {
        TaskStatus from = task.getStatus();
        if (!action.allows(from)) {
            throw new BusinessException(ErrorCode.TASK_INVALID_TRANSITION,
                    "当前状态（" + from.label() + "）不允许执行「" + action.label() + "」");
        }
        mutator.accept(task);
        taskRepository.saveAndFlush(task);

        TaskEvent event = new TaskEvent();
        event.setTaskId(task.getId());
        event.setAction(action.name());
        event.setActorId(actor.id());
        event.setFromStatus(from.name());
        event.setToStatus(task.getStatus().name());
        event.setReason(reason);
        eventRepository.saveAndFlush(event);

        return taskService.load(task.getId(), actor, clock.today());
    }

    private void notify(Task task, Actor actor, NotifyType type, String body, long eventId) {
        if (eventId <= 0) {
            return;
        }
        notificationService.createIfAbsent(CreateNotificationCommand.async(
                task.getOwnerId(), type, task.getId(), task.getTitle(),
                type.label() + "：" + task.getTitle(), body,
                NotificationKeys.event(task.getId(), eventId, type), "/tasks/" + task.getId()));
    }

    private long lastEventId(long taskId) {
        List<Long> ids = jdbc.query(
                "SELECT MAX(id) FROM task_events WHERE task_id = ?",
                (rs, i) -> rs.getLong(1), taskId);
        return ids.isEmpty() || ids.get(0) == null ? 0 : ids.get(0);
    }

    private Task lock(long id) {
        return taskRepository.findByIdForUpdate(id)
                .orElseThrow(() -> BusinessException.notFound("任务不存在"));
    }

    private String requireText(String value, String field, String message, int min, int max) {
        if (value == null || value.trim().length() < min) {
            throw BusinessException.validation(field, message);
        }
        String text = value.trim();
        if (text.length() > max) {
            throw BusinessException.validation(field, "内容不能超过 " + max + " 字");
        }
        return text;
    }

    private String optionalText(String value, String field, int max) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String text = value.trim();
        if (text.length() > max) {
            throw BusinessException.validation(field, "内容不能超过 " + max + " 字");
        }
        return text;
    }

    private void requireActiveUser(long userId, String field) {
        List<String> rows = jdbc.query(
                "SELECT status FROM users WHERE id = ?", (rs, i) -> rs.getString(1), userId);
        if (rows.isEmpty()) {
            throw BusinessException.validation(field, "负责人不存在");
        }
        if (!"ACTIVE".equals(rows.get(0))) {
            throw BusinessException.validation(field, "负责人已停用");
        }
    }

    private String nameOf(long userId) {
        List<String> rows = jdbc.query("SELECT name FROM users WHERE id = ?", (rs, i) -> rs.getString(1), userId);
        return rows.isEmpty() ? ("#" + userId) : rows.get(0);
    }
}
