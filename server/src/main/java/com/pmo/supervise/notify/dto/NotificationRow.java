package com.pmo.supervise.notify.dto;

import com.pmo.supervise.common.DingTalkStatus;
import com.pmo.supervise.common.NotifyType;

import java.time.LocalDateTime;

/**
 * 通知行（TECHNICAL §5.6）。progressId 用于拼 {@code #progress-{id}} 深链，
 * 前端不解析 dedup_key。
 */
public record NotificationRow(
        long id,
        NotifyType type,
        String typeLabel,
        Long taskId,
        Long progressId,
        String taskTitle,
        String ownerName,
        LocalDateTime createdAt,
        DingTalkStatus channelStatus,
        String channelLabel,
        boolean read,
        int retryCount,
        String failureReason) {
}
