package com.pmo.supervise.notify;

import com.pmo.supervise.common.BusinessException;
import com.pmo.supervise.common.DingTalkStatus;
import com.pmo.supervise.common.NotifyType;
import com.pmo.supervise.common.ShanghaiClock;
import com.pmo.supervise.notify.dto.CreateNotificationCommand;
import com.pmo.supervise.notify.dto.NotificationPage;
import com.pmo.supervise.notify.dto.NotificationRow;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 通知写入与读取（TECHNICAL §4.6）：先 INSERT 再捕唯一键冲突才是并发下真正幂等；
 * 钉钉发送只更新同一行，未配置立即 NOT_CONFIGURED 不重试。
 */
@Service
public class NotificationService {

    private static final int[] BACKOFF_MINUTES = {1, 5, 15};
    private static final int MAX_ATTEMPTS = 3;

    private final NamedParameterJdbcTemplate jdbc;
    private final NotificationRepository repository;
    private final DingTalkSender sender;
    private final ShanghaiClock clock;
    private final ApplicationEventPublisher publisher;

    public NotificationService(NamedParameterJdbcTemplate jdbc, NotificationRepository repository,
                               DingTalkSender sender, ShanghaiClock clock, ApplicationEventPublisher publisher) {
        this.jdbc = jdbc;
        this.repository = repository;
        this.sender = sender;
        this.clock = clock;
        this.publisher = publisher;
    }

    /**
     * 幂等创建：dedup_key 唯一键冲突即视为已存在。返回空表示本次未新建。
     */
    @Transactional
    public Optional<Notification> createIfAbsent(CreateNotificationCommand cmd) {
        boolean configured = sender.isConfigured();
        boolean bound = configured && unionIdOf(cmd.userId()) != null;
        DingTalkStatus initial = !configured ? DingTalkStatus.NOT_CONFIGURED
                : (!bound ? DingTalkStatus.UNBOUND : DingTalkStatus.PENDING);
        LocalDateTime nextRetry = initial == DingTalkStatus.PENDING
                ? clock.now().plusMinutes(1) : null;

        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("userId", cmd.userId())
                .addValue("taskId", cmd.taskId())
                .addValue("type", cmd.type().name())
                .addValue("notifyDate", clock.today())
                .addValue("dedupKey", cmd.dedupKey())
                .addValue("title", truncate(cmd.title(), 120))
                .addValue("body", truncate(cmd.body(), 1000))
                .addValue("status", initial.name())
                .addValue("nextRetry", nextRetry);
        try {
            jdbc.update("""
                    INSERT INTO notifications
                        (user_id, task_id, type, notify_date, dedup_key, title, body,
                         dingtalk_status, dingtalk_attempts, dingtalk_next_retry_at)
                    VALUES (:userId, :taskId, :type, :notifyDate, :dedupKey, :title, :body,
                            :status, 0, :nextRetry)
                    """, params);
        } catch (DuplicateKeyException ex) {
            return Optional.empty();
        }

        Long id = jdbc.queryForObject(
                "SELECT id FROM notifications WHERE dedup_key = :dedupKey",
                Map.of("dedupKey", cmd.dedupKey()), Long.class);
        Notification notification = id == null ? null : repository.findById(id).orElse(null);
        if (notification != null && initial == DingTalkStatus.PENDING && cmd.async()) {
            publisher.publishEvent(new NotificationCreatedEvent(id));
        }
        return Optional.ofNullable(notification);
    }

    /** 事务提交后异步推送；非事务上下文（扫描任务）也会执行。 */
    @org.springframework.scheduling.annotation.Async("dingtalkExecutor")
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onCreated(NotificationCreatedEvent event) {
        dispatch(event.notificationId());
    }

    public DingTalkStatus dispatchNow(long notificationId) {
        return dispatch(notificationId);
    }

    /** 同步推送并回写渠道状态；永不抛异常。 */
    public DingTalkStatus dispatch(long notificationId) {
        Notification n = repository.findById(notificationId).orElse(null);
        if (n == null) {
            return DingTalkStatus.FAILED;
        }
        if (n.getDingtalkStatus() != DingTalkStatus.PENDING && n.getDingtalkStatus() != DingTalkStatus.FAILED) {
            return n.getDingtalkStatus();
        }
        if (n.getDingtalkAttempts() >= MAX_ATTEMPTS) {
            return n.getDingtalkStatus();
        }
        if (!sender.isConfigured()) {
            updateStatus(n.getId(), DingTalkStatus.NOT_CONFIGURED, n.getDingtalkAttempts(), null, null);
            return DingTalkStatus.NOT_CONFIGURED;
        }
        String unionId = unionIdOf(n.getUserId());
        if (unionId == null) {
            updateStatus(n.getId(), DingTalkStatus.UNBOUND, n.getDingtalkAttempts(), null, null);
            return DingTalkStatus.UNBOUND;
        }

        DingTalkStatus result = sender.sendWorkNotice(unionId, n.getTitle(), n.getBody(), urlFor(n));
        int attempts = n.getDingtalkAttempts() + 1;
        if (result == DingTalkStatus.SENT) {
            updateStatus(n.getId(), DingTalkStatus.SENT, attempts, null, null);
            return DingTalkStatus.SENT;
        }
        if (attempts >= MAX_ATTEMPTS) {
            updateStatus(n.getId(), DingTalkStatus.FAILED, attempts, null, "已达最大重试次数");
            return DingTalkStatus.FAILED;
        }
        LocalDateTime next = clock.now().plusMinutes(BACKOFF_MINUTES[attempts - 1]);
        updateStatus(n.getId(), DingTalkStatus.FAILED, attempts, next, "发送失败");
        return DingTalkStatus.FAILED;
    }

    @Transactional(readOnly = true)
    public NotificationPage page(long userId, boolean unreadOnly, int page, int size) {
        int safePage = Math.max(page, 1);
        int safeSize = Math.min(Math.max(size, 1), 100);
        String where = " WHERE n.user_id = :userId" + (unreadOnly ? " AND n.read_at IS NULL" : "");
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("userId", userId);
        params.put("limit", safeSize);
        params.put("offset", (long) (safePage - 1) * safeSize);

        Long total = jdbc.queryForObject(
                "SELECT COUNT(*) FROM notifications n" + where, params, Long.class);
        List<NotificationRow> items = jdbc.query("""
                SELECT n.id, n.type, n.task_id, n.title, n.created_at, n.dingtalk_status, n.read_at,
                       n.dingtalk_attempts, n.dingtalk_last_error,
                       t.title AS task_title, u.name AS owner_name
                FROM notifications n
                LEFT JOIN tasks t ON t.id = n.task_id
                LEFT JOIN users u ON u.id = t.owner_id
                """ + where + " ORDER BY n.created_at DESC, n.id DESC LIMIT :limit OFFSET :offset",
                params, (rs, rowNum) -> {
                    NotifyType type = NotifyType.of(rs.getString("type"));
                    DingTalkStatus status = DingTalkStatus.of(rs.getString("dingtalk_status"));
                    java.sql.Timestamp createdAt = rs.getTimestamp("created_at");
                    return new NotificationRow(
                            rs.getLong("id"), type, type.label(),
                            rs.getObject("task_id", Long.class),
                            rs.getString("task_title"),
                            rs.getString("owner_name"),
                            createdAt == null ? null : createdAt.toLocalDateTime(),
                            status, status == null ? null : status.label(),
                            rs.getTimestamp("read_at") != null,
                            rs.getInt("dingtalk_attempts"),
                            rs.getString("dingtalk_last_error"));
                });
        return new NotificationPage(items, total == null ? 0 : total, safePage, safeSize,
                unreadCount(userId));
    }

    public long unreadCount(long userId) {
        Long count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM notifications WHERE user_id = :userId AND read_at IS NULL",
                Map.of("userId", userId), Long.class);
        return count == null ? 0 : count;
    }

    public void markRead(long id, long userId) {
        int updated = jdbc.update("""
                UPDATE notifications SET read_at = :now
                WHERE id = :id AND user_id = :userId AND read_at IS NULL
                """, Map.of("now", clock.now(), "id", id, "userId", userId));
        if (updated == 0 && !existsFor(id, userId)) {
            throw BusinessException.notFound("通知不存在");
        }
    }

    public int markAllRead(long userId) {
        return jdbc.update("""
                UPDATE notifications SET read_at = :now
                WHERE user_id = :userId AND read_at IS NULL
                """, Map.of("now", clock.now(), "userId", userId));
    }

    private boolean existsFor(long id, long userId) {
        Long count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM notifications WHERE id = :id AND user_id = :userId",
                Map.of("id", id, "userId", userId), Long.class);
        return count != null && count > 0;
    }

    private void updateStatus(Long id, DingTalkStatus status, int attempts,
                              LocalDateTime nextRetryAt, String error) {
        jdbc.update("""
                UPDATE notifications
                SET dingtalk_status = :status, dingtalk_attempts = :attempts,
                    dingtalk_next_retry_at = :nextRetry, dingtalk_last_error = :error
                WHERE id = :id
                """, new MapSqlParameterSource()
                .addValue("status", status.name())
                .addValue("attempts", attempts)
                .addValue("nextRetry", nextRetryAt)
                .addValue("error", error)
                .addValue("id", id));
    }

    private String unionIdOf(long userId) {
        List<String> rows = jdbc.query(
                "SELECT dingtalk_union_id FROM users WHERE id = :id",
                java.util.Map.of("id", userId),
                (rs, i) -> rs.getString(1));
        if (rows.isEmpty() || rows.get(0) == null || rows.get(0).isBlank()) {
            return null;
        }
        return rows.get(0);
    }

    private String urlFor(Notification n) {
        if (n.getTaskId() == null) {
            return null;
        }
        return "/tasks/" + n.getTaskId();
    }

    private static String truncate(String value, int max) {
        if (value == null) {
            return "";
        }
        return value.length() <= max ? value : value.substring(0, max);
    }
}
