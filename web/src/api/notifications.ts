/**
 * 通知接口（TECHNICAL §5.5）：全部为「本人视角」，越权由后端按 404 处理（AC-13）。
 * 列表返回 unreadCount，页面据此同步顶栏角标，不再单独猜数字。
 */
import { apiGet, apiPost } from './http'
import type { NotificationPage } from '@/types/api'

export interface NotificationListQuery {
  unreadOnly?: boolean
  page?: number
  size?: number
}

export const notificationsApi = {
  list(query: NotificationListQuery = {}): Promise<NotificationPage> {
    return apiGet<NotificationPage>('/notifications', {
      params: {
        unreadOnly: query.unreadOnly ?? false,
        page: query.page ?? 1,
        size: query.size ?? 20,
      },
    })
  },

  unreadCount(): Promise<{ count: number }> {
    return apiGet<{ count: number }>('/notifications/unread-count')
  },

  /** 置为已读；非本人 → 404（与任务探测同一语义） */
  markRead(id: number): Promise<void> {
    return apiPost<void>(`/notifications/${id}/read`)
  },

  markAllRead(): Promise<{ updated: number }> {
    return apiPost<{ updated: number }>('/notifications/read-all')
  },
}
