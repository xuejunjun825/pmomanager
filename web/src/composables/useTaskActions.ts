/**
 * 状态流转的唯一执行入口（TECHNICAL §4.2 / AC-9）。
 *
 * 页面不直接拼接口，也不自己判权限：能不能点由服务端 permissions 决定，
 * 这里只负责发请求、把 409 变成「正在刷新」、并给出「结果 + 下一步」的反馈文案。
 */
import { ref } from 'vue'
import { toApiError, type ApiError } from '@/api/http'
import { taskApi } from '@/api/tasks'
import { useToast } from '@/composables/useToast'
import { formatDate } from '@/utils/format'
import type { SubmitCommand, TaskDetail } from '@/types/api'

export type TransitionAction =
  | 'start'
  | 'submit'
  | 'accept'
  | 'reject'
  | 'cancel'
  | 'restore'
  | 'forceComplete'
  | 'reassign'
  | 'reschedule'

export interface TransitionPayload {
  reason?: string
  ownerId?: number
  dueDate?: string
  command?: SubmitCommand
}

interface TaskActionsOptions {
  onUpdated?: (detail: TaskDetail) => void
  /** 409：状态已被别人改过，页面应按服务端最新状态重取详情 */
  onConflict?: (error: ApiError) => void
}

function successText(action: TransitionAction, detail: TaskDetail): string {
  switch (action) {
    case 'start':
      return '任务已开始，进入「进行中」'
    case 'submit':
      return '已提交验收，等待派发人确认'
    case 'accept':
      return '验收通过，任务已完成'
    case 'reject':
      return '已打回，任务回到「进行中」'
    case 'cancel':
      return '任务已取消'
    case 'restore':
      return '已恢复为「待开始」'
    case 'forceComplete':
      return '已代确认完成'
    case 'reassign':
      return `已改派给 ${detail.owner?.name ?? '新负责人'}`
    case 'reschedule':
      return `截止日期已改为 ${formatDate(detail.dueDate)}`
    default:
      return '已更新'
  }
}

function call(taskId: number, action: TransitionAction, payload: TransitionPayload): Promise<TaskDetail> {
  switch (action) {
    case 'start':
      return taskApi.start(taskId)
    case 'submit':
      return taskApi.submit(taskId, payload.command ?? {})
    case 'accept':
      return taskApi.accept(taskId)
    case 'reject':
      return taskApi.reject(taskId, payload.reason ?? '')
    case 'cancel':
      return taskApi.cancel(taskId, payload.reason ?? '')
    case 'restore':
      return taskApi.restore(taskId)
    case 'forceComplete':
      return taskApi.forceComplete(taskId, payload.reason ?? '')
    case 'reassign':
      return taskApi.reassign(taskId, Number(payload.ownerId ?? 0), payload.reason ?? '')
    case 'reschedule':
      return taskApi.reschedule(taskId, payload.dueDate ?? '', payload.reason ?? '')
  }
}

export function useTaskActions(options: TaskActionsOptions = {}) {
  const toast = useToast()
  const busy = ref<TransitionAction | ''>('')
  const lastError = ref<ApiError | null>(null)

  async function run(
    taskId: number,
    action: TransitionAction,
    payload: TransitionPayload = {},
  ): Promise<TaskDetail | null> {
    if (busy.value) return null
    busy.value = action
    lastError.value = null
    try {
      const detail = await call(taskId, action, payload)
      if (detail) {
        options.onUpdated?.(detail)
        toast.success(successText(action, detail))
      }
      return detail
    } catch (error) {
      const apiError = toApiError(error)
      lastError.value = apiError
      if (apiError.status === 409) {
        // 非法流转：把当前状态喊出来，并让页面重取一次，避免界面停在旧状态
        toast.warning(apiError.message || '任务当前状态已变化，正在刷新最新状态')
        options.onConflict?.(apiError)
      } else if (apiError.status === 403) {
        toast.error(apiError.message || '没有执行该操作的权限')
      } else if (apiError.status === 404) {
        toast.error('任务不存在，或你没有权限查看它')
      } else {
        toast.error(apiError.message || '操作失败，请稍后重试')
      }
      return null
    } finally {
      busy.value = ''
    }
  }

  return { run, busy, lastError }
}
