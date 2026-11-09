package com.pmo.supervise.notify.dto;

import java.util.List;

/** 通知分页 + 未读数（前端 NotificationPage）。 */
public record NotificationPage(List<NotificationRow> items, long total, int page, int size, long unreadCount) {
}
