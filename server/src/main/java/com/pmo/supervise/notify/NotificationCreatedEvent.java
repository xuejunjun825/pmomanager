package com.pmo.supervise.notify;

/**
 * 通知行已入库，等待异步推送钉钉（事务提交后触发）。
 */
public record NotificationCreatedEvent(long notificationId) {
}
