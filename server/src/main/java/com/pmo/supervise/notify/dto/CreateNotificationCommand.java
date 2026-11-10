package com.pmo.supervise.notify.dto;

import com.pmo.supervise.common.NotifyType;

/**
 * 创建通知的命令。async=true 时事务提交后异步推送钉钉；
 * 催办走同步（async=false）以便立即拿到渠道结果。
 *
 * <p>progressId 必须随命令进入 INSERT：深链 {@code /tasks/{id}#progress-{pid}} 就靠它，
 * 不能事后解析 dedup_key 反推。
 */
public record CreateNotificationCommand(long userId, NotifyType type, Long taskId, Long progressId,
                                        String taskTitle, String title, String body, String dedupKey,
                                        String url, boolean async) {

    /** 兼容不带进度锚点的既有调用（催办/扫描/报表）。 */
    public CreateNotificationCommand(long userId, NotifyType type, Long taskId, String taskTitle,
                                     String title, String body, String dedupKey, String url,
                                     boolean async) {
        this(userId, type, taskId, null, taskTitle, title, body, dedupKey, url, async);
    }

    public static CreateNotificationCommand async(long userId, NotifyType type, Long taskId, String taskTitle,
                                                  String title, String body, String dedupKey, String url) {
        return new CreateNotificationCommand(userId, type, taskId, null, taskTitle, title, body,
                dedupKey, url, true);
    }
}
