/**
 * 界面状态：侧栏折叠（localStorage 持久化）+ 全局 Toast（最多 2 条，3s 自动消失）。
 * Toast 是唯一允许的「操作结果」出口——页面不自己弹 alert。
 */
import { ref } from 'vue'
import { defineStore } from 'pinia'

export type ToastTone = 'success' | 'danger' | 'warning' | 'info'

export interface ToastItem {
  id: number
  tone: ToastTone
  message: string
  duration: number
}

const COLLAPSE_KEY = 'pmo:nav-collapsed'
const MAX_TOASTS = 2
const DEFAULT_DURATION = 3000

function readCollapsed(): boolean {
  try {
    return localStorage.getItem(COLLAPSE_KEY) === '1'
  } catch {
    return false
  }
}

function persistCollapsed(value: boolean): void {
  try {
    localStorage.setItem(COLLAPSE_KEY, value ? '1' : '0')
  } catch {
    // 隐私模式下 localStorage 不可写，忽略即可
  }
}

export const useUiStore = defineStore('ui', () => {
  const sidebarCollapsed = ref(readCollapsed())
  const toasts = ref<ToastItem[]>([])
  const timers = new Map<number, ReturnType<typeof setTimeout>>()
  let seq = 0

  function toggleSidebar(): void {
    setSidebarCollapsed(!sidebarCollapsed.value)
  }

  function setSidebarCollapsed(value: boolean): void {
    sidebarCollapsed.value = value
    persistCollapsed(value)
  }

  function dismissToast(id: number): void {
    const timer = timers.get(id)
    if (timer) {
      clearTimeout(timer)
      timers.delete(id)
    }
    toasts.value = toasts.value.filter((item) => item.id !== id)
  }

  function pushToast(input: { message: string; tone?: ToastTone; duration?: number }): number {
    const id = ++seq
    const item: ToastItem = {
      id,
      tone: input.tone ?? 'info',
      message: input.message,
      duration: input.duration ?? DEFAULT_DURATION,
    }
    toasts.value = [...toasts.value, item]
    // 超出上限时先淘汰最旧的一条，防止堆满右上角
    while (toasts.value.length > MAX_TOASTS) {
      const oldest = toasts.value[0]
      if (oldest) dismissToast(oldest.id)
      else break
    }
    if (typeof setTimeout === 'function' && item.duration > 0) {
      timers.set(
        id,
        setTimeout(() => dismissToast(id), item.duration),
      )
    }
    return id
  }

  function clearToasts(): void {
    timers.forEach((timer) => clearTimeout(timer))
    timers.clear()
    toasts.value = []
  }

  return { sidebarCollapsed, toasts, toggleSidebar, setSidebarCollapsed, pushToast, dismissToast, clearToasts }
})
