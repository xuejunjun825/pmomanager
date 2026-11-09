/**
 * 未读通知计数（顶栏角标）。通知正文不入 store——列表页自己拉分页数据。
 */
import { ref } from 'vue'
import { defineStore } from 'pinia'
import { apiGet, apiPost } from '@/api/http'

export const useNotificationsStore = defineStore('notifications', () => {
  const unreadCount = ref(0)
  const loading = ref(false)

  async function refreshUnread(): Promise<void> {
    loading.value = true
    try {
      const data = await apiGet<{ count?: number }>('/notifications/unread-count')
      unreadCount.value = typeof data?.count === 'number' ? data.count : 0
    } catch {
      // 角标失败不打扰用户（下一次路由切换会重试）
    } finally {
      loading.value = false
    }
  }

  async function markAllRead(): Promise<number> {
    const data = await apiPost<{ updated?: number }>('/notifications/read-all')
    unreadCount.value = 0
    return data?.updated ?? 0
  }

  /** 单条已读：本地先减，避免为了一个角标再发一次请求 */
  function decrement(): void {
    if (unreadCount.value > 0) unreadCount.value -= 1
  }

  function setUnread(count: number): void {
    unreadCount.value = Math.max(0, Number(count) || 0)
  }

  function reset(): void {
    unreadCount.value = 0
  }

  return { unreadCount, loading, refreshUnread, markAllRead, decrement, setUnread, reset }
})
