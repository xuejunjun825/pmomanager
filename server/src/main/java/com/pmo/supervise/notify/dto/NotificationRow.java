package com.pmo.supervise.notify.dto;

import com.pmo.supervise.common.DingTalkStatus;
import com.pmo.supervise.common.NotifyType;

import java.time.LocalDateTime;

/** 通知列表行（前端 NotificationRow）。 */
public record NotificationRow(
        long id,
        NotifyType type,
        String typeLabel,
        Long taskId,
        String taskTitle,
        String ownerName,
        LocalDateTime createdAt,
        DingTalkStatus channelStatus,
        String channelLabel,
        boolean read,
        /** 钉钉已尝试次数（dingtalk_attempts） */
        int retryCount,
        /** 最近一次失败原因（dingtalk_last_error），成功后为 null */
        String failureReason) {
}
