package com.pmo.supervise.notify.dto;

import com.pmo.supervise.common.NotifyType;

/**
 * 创建通知的命令。async=true 时事务提交后异步推送钉钉；
 * 催办走同步（async=false）以便立即拿到渠道结果。
 */
public record CreateNotificationCommand(long userId, NotifyType type, Long taskId, String taskTitle,
                                       String title, String body, String dedupKey, String url,
                                       boolean async) {

    public static CreateNotificationCommand async(long userId, NotifyType type, Long taskId, String taskTitle,
                                                  String title, String body, String dedupKey, String url) {
        return new CreateNotificationCommand(userId, type, taskId, taskTitle, title, body, dedupKey, url, true);
    }
}
