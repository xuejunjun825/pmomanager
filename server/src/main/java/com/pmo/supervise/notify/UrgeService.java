package com.pmo.supervise.notify;

import com.pmo.supervise.common.Actor;
import com.pmo.supervise.common.BusinessException;
import com.pmo.supervise.common.DingTalkStatus;
import com.pmo.supervise.common.ErrorCode;
import com.pmo.supervise.common.NotifyType;
import com.pmo.supervise.common.ShanghaiClock;
import com.pmo.supervise.notify.dto.CreateNotificationCommand;
import com.pmo.supervise.notify.dto.UrgeResult;
import com.pmo.supervise.task.Task;
import com.pmo.supervise.task.TaskAccessGuard;
import com.pmo.supervise.task.TaskRepository;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.Optional;

/**
 * 一键催办（TECHNICAL §4.6）：10 分钟窗口按 (任务, 操作人)，
 * 窗口内第二次返回 409 URGE_TOO_SOON 并带 lastUrgedAt。
 */
@Service
public class UrgeService {

    private static final int WINDOW_MINUTES = 10;

    private final TaskRepository taskRepository;
    private final UrgeRecordRepository urgeRecordRepository;
    private final NotificationService notificationService;
    private final TaskAccessGuard guard;
    private final ShanghaiClock clock;
    private final NamedParameterJdbcTemplate jdbc;

    public UrgeService(TaskRepository taskRepository, UrgeRecordRepository urgeRecordRepository,
                       NotificationService notificationService, TaskAccessGuard guard,
                       ShanghaiClock clock, NamedParameterJdbcTemplate jdbc) {
        this.taskRepository = taskRepository;
        this.urgeRecordRepository = urgeRecordRepository;
        this.notificationService = notificationService;
        this.guard = guard;
        this.clock = clock;
        this.jdbc = jdbc;
    }

    @Transactional
    public UrgeResult urge(long taskId, Actor actor) {
        guard.requireUrge(actor);
        Task task = taskRepository.findByIdForUpdate(taskId)
                .orElseThrow(() -> BusinessException.notFound("任务不存在"));
        if (task.getStatus().isFinished()) {
            throw new BusinessException(ErrorCode.TASK_FINISHED, "任务已结束，无法催办");
        }

        LocalDateTime now = clock.now();
        Optional<UrgeRecord> last = urgeRecordRepository
                .findFirstByTaskIdAndActorIdOrderByCreatedAtDesc(taskId, actor.id());
        if (last.isPresent() && last.get().getCreatedAt() != null
                && last.get().getCreatedAt().isAfter(now.minusMinutes(WINDOW_MINUTES))) {
            throw new BusinessException(ErrorCode.URGE_TOO_SOON, "10 分钟内已催办过该任务",
                    Map.of("lastUrgedAt", last.get().getCreatedAt()));
        }

        long sequence = countToday(taskId, actor.id()) + 1;
        String body = MessageTemplates.urgeBody(actor.name(), task.getTitle(), task.getDueDate(), sequence);
        Notification created = notificationService.createIfAbsent(new CreateNotificationCommand(
                        task.getOwnerId(), NotifyType.URGE, taskId, task.getTitle(),
                        MessageTemplates.urgeTitle(task.getTitle()), body,
                        NotificationKeys.urge(taskId, clock.today(), actor.id(), sequence),
                        "/tasks/" + taskId, false))
                .orElse(null);

        DingTalkStatus status = DingTalkStatus.SKIPPED;
        if (created != null) {
            status = notificationService.dispatchNow(created.getId());
        }

        UrgeRecord record = new UrgeRecord();
        record.setTaskId(taskId);
        record.setActorId(actor.id());
        record.setDingtalkStatus(status);
        urgeRecordRepository.saveAndFlush(record);

        long urgeCount = urgeRecordRepository.countByTaskId(taskId);
        return new UrgeResult(urgeCount, now, status,
                status == null ? null : status.label(), messageFor(status));
    }

    private long countToday(long taskId, long actorId) {
        Long count = jdbc.queryForObject("""
                SELECT COUNT(*) FROM urge_records
                WHERE task_id = :taskId AND actor_id = :actorId AND created_at >= :from
                """, Map.of("taskId", taskId, "actorId", actorId,
                "from", clock.today().atStartOfDay()), Long.class);
        return count == null ? 0 : count;
    }

    private String messageFor(DingTalkStatus status) {
        if (status == null) {
            return "已记录催办";
        }
        return switch (status) {
            case SENT -> "已通过钉钉提醒负责人";
            case NOT_CONFIGURED -> "钉钉未配置，已发送站内提醒";
            case UNBOUND -> "负责人未绑定钉钉，已发送站内提醒";
            case FAILED -> "钉钉发送失败，已保留站内提醒并将自动重试";
            default -> "已记录催办";
        };
    }
}
