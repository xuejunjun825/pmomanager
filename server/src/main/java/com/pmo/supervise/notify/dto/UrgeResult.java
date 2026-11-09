package com.pmo.supervise.notify.dto;

import com.pmo.supervise.common.DingTalkStatus;

import java.time.LocalDateTime;

/** 一键催办结果（前端 UrgeResult）。 */
public record UrgeResult(long urgeCount, LocalDateTime lastUrgedAt,
                         DingTalkStatus channelStatus, String channelLabel, String message) {
}
