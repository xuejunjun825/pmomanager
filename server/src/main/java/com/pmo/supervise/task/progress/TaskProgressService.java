package com.pmo.supervise.task.progress;

import com.pmo.supervise.common.Actor;
import com.pmo.supervise.common.BusinessException;
import com.pmo.supervise.common.ErrorCode;
import com.pmo.supervise.common.NotifyType;
import com.pmo.supervise.notify.MessageTemplates;
import com.pmo.supervise.notify.NotificationKeys;
import com.pmo.supervise.notify.NotificationService;
import com.pmo.supervise.notify.dto.CreateNotificationCommand;
import com.pmo.supervise.task.Task;
import com.pmo.supervise.task.TaskAccessGuard;
import com.pmo.supervise.task.TaskRepository;
import com.pmo.supervise.task.dto.UserRef;
import com.pmo.supervise.task.progress.dto.AddProgressRequest;
import com.pmo.supervise.task.progress.dto.ProgressPage;
import com.pmo.supervise.task.progress.dto.ProgressRow;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Objects;

/**
 * 追加进度与被@通知（TECHNICAL §4.4）。
 *
 * <p>写进度先锁 tasks 行：200 条上限与 @ 校验必须在同一事务里判定，
 * 否则并发追加可以把条数顶过上限。进度只写 task_progress / task_progress_mentions，
 * 不碰 tasks 状态、task_events 与看板计数（AC-12 的口径隔离）。
 */
@Service
public class TaskProgressService {

    private static final int MAX_PER_TASK = 200;
    private static final int MAX_MENTIONS = 10;
    private static final int MAX_CONTENT = 500;
    private static final int MAX_PAGE = 100;

    private final TaskRepository taskRepository;
    private final TaskAccessGuard guard;
    private final TaskProgressDao progressDao;
    private final NotificationService notificationService;

    public TaskProgressService(TaskRepository taskRepository, TaskAccessGuard guard,
                               TaskProgressDao progressDao, NotificationService notificationService) {
        this.taskRepository = taskRepository;
        this.guard = guard;
        this.progressDao = progressDao;
        this.notificationService = notificationService;
    }

    @Transactional
    public ProgressRow add(long taskId, Actor actor, AddProgressRequest req) {
        Task task = taskRepository.findByIdForUpdate(taskId)
                .orElseThrow(() -> BusinessException.notFound("任务不存在"));
        guard.requireProgressWrite(actor, task);

        String content = req.content() == null ? "" : req.content().trim();
        if (content.isEmpty() || content.codePointCount(0, content.length()) > MAX_CONTENT) {
            throw BusinessException.validation("content", "进度内容需 1–500 字");
        }
        List<Long> mentioned = normalizeMentions(req.mentionedUserIds(), actor);

        if (progressDao.countByTask(taskId) >= MAX_PER_TASK) {
            throw new BusinessException(ErrorCode.PROGRESS_LIMIT_REACHED, "该任务进度已达 200 条上限");
        }

        long progressId = progressDao.insert(taskId, actor.id(), content);
        if (!mentioned.isEmpty()) {
            progressDao.insertMentions(progressId, mentioned);
            notifyMentions(task, progressId, actor, content, mentioned);
        }
        return progressDao.findById(progressId)
                .orElseThrow(() -> new IllegalStateException("进度写入后读取失败"))
                .withMentionedMe(mentioned.contains(actor.id()));
    }

    @Transactional(readOnly = true)
    public ProgressPage page(long taskId, Actor actor, int limit, Long beforeId) {
        Task task = taskRepository.findById(taskId)
                .orElseThrow(() -> BusinessException.notFound("任务不存在"));
        guard.requireView(actor, task);

        int safeLimit = Math.min(Math.max(limit, 1), MAX_PAGE);
        List<ProgressRow> items = progressDao.page(taskId, safeLimit, beforeId).stream()
                .map(row -> row.withMentionedMe(isMentionedIn(row, actor.id())))
                .toList();
        long total = progressDao.countAll(taskId);
        boolean hasMore = beforeId == null ? total > items.size() : items.size() == safeLimit;
        return new ProgressPage(items, total, hasMore);
    }

    /** 去重、剔除自己、限 10 人、只允许启用成员（AC-9）。 */
    private List<Long> normalizeMentions(List<Long> raw, Actor actor) {
        if (raw == null || raw.isEmpty()) {
            return List.of();
        }
        List<Long> distinct = raw.stream()
                .filter(Objects::nonNull)
                .distinct()
                .filter(id -> id != actor.id())
                .toList();
        if (distinct.isEmpty()) {
            return List.of();
        }
        if (distinct.size() > MAX_MENTIONS) {
            throw BusinessException.validation("mentionedUserIds", "最多 @ 10 人");
        }
        List<Long> active = progressDao.activeUserIds(distinct);
        if (active.size() != distinct.size()) {
            throw BusinessException.validation("mentionedUserIds", "只能 @ 启用成员");
        }
        return distinct.stream().filter(active::contains).toList();
    }

    /** 通知与进度同事务写入，提交后才异步推钉钉；未配置按 NOT_CONFIGURED 降级，不阻塞。 */
    private void notifyMentions(Task task, long progressId, Actor actor, String content, List<Long> mentioned) {
        String url = "/tasks/" + task.getId() + "#progress-" + progressId;
        for (Long userId : mentioned) {
            notificationService.createIfAbsent(new CreateNotificationCommand(
                    userId,
                    NotifyType.MENTION,
                    task.getId(),
                    progressId,
                    task.getTitle(),
                    MessageTemplates.mentionTitle(actor.name(), task.getTitle()),
                    MessageTemplates.mentionBody(content),
                    NotificationKeys.mention(progressId, userId),
                    url,
                    true));
        }
    }

    private boolean isMentionedIn(ProgressRow row, long userId) {
        List<UserRef> mentions = row.mentions();
        return mentions != null && mentions.stream().anyMatch(ref -> ref.id() == userId);
    }
}
