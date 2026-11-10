/**
 * 任务域接口（TECHNICAL §5.4）。
 * 读侧只发筛选参数，只收服务端算好的 is_overdue / overdue_days / due_soon / permissions；
 * 写侧每个流转一条子路径，让权限与测试逐条对应（不用一个大 action 入口）。
 */
import { apiGet, apiPatch, apiPost } from './http'
import type {
  AddProgressPayload,
  CreateTaskRequest,
  DecisionItemRow,
  MeetingDetail,
  PageResponse,
  ProgressPage,
  ProgressRow,
  SubmitCommand,
  TaskDetail,
  TaskEvent,
  TaskFilter,
  TaskRow,
  UpdateTaskRequest,
  UrgeResult,
} from '@/types/api'

export interface TaskPageCounters {
  overdue: number
  pendingAccept: number
}

function toParams(filter: TaskFilter): Record<string, string | number> {
  const params: Record<string, string | number> = {}
  if (filter.status) params.status = filter.status
  if (filter.ownerId !== undefined && filter.ownerId !== '') params.ownerId = filter.ownerId
  if (filter.sourceType) params.sourceType = filter.sourceType
  // 项目：`'<id>'` / `'none'` / `''`（全部）原样透传，解析只在服务端 ProjectFilterSql
  if (filter.project) params.project = filter.project
  if (filter.meetingId !== undefined && filter.meetingId !== '') params.meetingId = filter.meetingId
  if (typeof filter.overdue === 'boolean') params.overdue = String(filter.overdue)
  if (filter.keyword && filter.keyword.trim()) params.keyword = filter.keyword.trim()
  if (filter.sort) params.sort = filter.sort
  params.page = filter.page ?? 1
  params.size = filter.size ?? 20
  return params
}

export const taskApi = {
  list(filter: TaskFilter = {}): Promise<PageResponse<TaskRow>> {
    return apiGet<PageResponse<TaskRow>>('/tasks', { params: toParams(filter) })
  },

  /**
   * 任务池汇总行的「逾期 / 待验收」两数。
   * TECHNICAL 未把 TaskQueryDao.counters 暴露成 HTTP（设计缺口），这里用 size=1 的列表查询
   * 取服务端 total —— 与列表同一套 SQL、同一个「今天」，不会出现第二套口径；
   * 后端补上 /tasks/counters 后只需改这一个方法。
   * 统计范围 = 当前筛选去掉「状态」「是否逾期」两项（否则两个数字会互相吞掉）。
   */
  async counters(filter: TaskFilter = {}): Promise<TaskPageCounters> {
    const scope: TaskFilter = { ...filter, status: '', overdue: '', page: 1, size: 1 }
    const [overdue, pendingAccept] = await Promise.all([
      apiGet<PageResponse<TaskRow>>('/tasks', {
        params: toParams({ ...scope, overdue: true }),
      }),
      apiGet<PageResponse<TaskRow>>('/tasks', {
        params: toParams({ ...scope, status: 'PENDING_ACCEPT' }),
      }),
    ])
    return {
      overdue: Number(overdue?.total ?? 0),
      pendingAccept: Number(pendingAccept?.total ?? 0),
    }
  },

  get(id: number): Promise<TaskDetail> {
    return apiGet<TaskDetail>(`/tasks/${id}`)
  },

  events(id: number): Promise<TaskEvent[]> {
    return apiGet<TaskEvent[]>(`/tasks/${id}/events`)
  },

  create(body: CreateTaskRequest): Promise<TaskDetail> {
    return apiPost<TaskDetail>('/tasks', body)
  },

  /** 来源字段不可改：改人/改期走 reassign / reschedule，才留得下流转记录 */
  update(id: number, body: UpdateTaskRequest): Promise<TaskDetail> {
    return apiPatch<TaskDetail>(`/tasks/${id}`, body)
  },

  start(id: number): Promise<TaskDetail> {
    return apiPost<TaskDetail>(`/tasks/${id}/start`)
  },

  submit(id: number, body: SubmitCommand = {}): Promise<TaskDetail> {
    return apiPost<TaskDetail>(`/tasks/${id}/submit`, body)
  },

  accept(id: number): Promise<TaskDetail> {
    return apiPost<TaskDetail>(`/tasks/${id}/accept`)
  },

  reject(id: number, reason: string): Promise<TaskDetail> {
    return apiPost<TaskDetail>(`/tasks/${id}/reject`, { reason })
  },

  cancel(id: number, reason: string): Promise<TaskDetail> {
    return apiPost<TaskDetail>(`/tasks/${id}/cancel`, { reason })
  },

  restore(id: number): Promise<TaskDetail> {
    return apiPost<TaskDetail>(`/tasks/${id}/restore`)
  },

  forceComplete(id: number, reason: string): Promise<TaskDetail> {
    return apiPost<TaskDetail>(`/tasks/${id}/force-complete`, { reason })
  },

  reassign(id: number, ownerId: number, reason: string): Promise<TaskDetail> {
    return apiPost<TaskDetail>(`/tasks/${id}/reassign`, { ownerId, reason })
  },

  reschedule(id: number, dueDate: string, reason: string): Promise<TaskDetail> {
    return apiPost<TaskDetail>(`/tasks/${id}/reschedule`, { dueDate, reason })
  },

  /** 409 URGE_TOO_SOON / TASK_FINISHED 由调用方分别处理 */
  urge(id: number): Promise<UrgeResult> {
    return apiPost<UrgeResult>(`/tasks/${id}/urges`)
  },

  /**
   * 抽屉里「会议决议」来源的决议项下拉：GET /api/meetings/{id}（TECHNICAL §5.3）。
   * 会议 API 归 T09 的 api/meetings.ts；落地后此处改为引用，避免两份会议读法。
   */
  async decisionItems(meetingId: number): Promise<DecisionItemRow[]> {
    const meeting = await apiGet<MeetingDetail>(`/meetings/${meetingId}`)
    return Array.isArray(meeting?.items) ? meeting.items : []
  },

  /** 进度时间线（倒序分页：beforeId 为上一页最小的进度 id） */
  progress(
    taskId: number,
    params: { limit?: number; beforeId?: number } = {},
  ): Promise<ProgressPage> {
    const query: Record<string, number> = {}
    if (params.limit) query.limit = params.limit
    if (params.beforeId) query.beforeId = params.beforeId
    return apiGet<ProgressPage>(`/tasks/${taskId}/progress`, { params: query })
  },

  /** 追加进度；403 PROGRESS_FORBIDDEN / 409 PROGRESS_LIMIT_REACHED 由调用方各自处理 */
  addProgress(taskId: number, payload: AddProgressPayload): Promise<ProgressRow> {
    return apiPost<ProgressRow>(`/tasks/${taskId}/progress`, payload)
  },
}
