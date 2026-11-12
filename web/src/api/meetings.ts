/**
 * 会议域接口（TECHNICAL §5.3 / AC-3 / AC-4 / AC-5）。
 * 会议只归档不删除；决议项一旦转任务即被锁定，来源快照不会变。
 * 转任务是幂等的：重复勾选只会进 skipped，所以返回值必须原样展示 created / skipped。
 */
import { apiDelete, apiGet, apiPost, apiPut } from './http'
import type {
  ConvertResult,
  DecisionItemRequest,
  DecisionItemRow,
  MeetingDetail,
  MeetingRequest,
  MeetingRow,
  PageResponse,
  TaskRow,
} from '@/types/api'

/**
 * 建会请求（r7 / AC-2）：在既有 `MeetingRequest` 之上带 0..N 条决议。
 * `items` 为空/缺省时与 r1 完全一致（只建会议），旧调用方零变化。
 * 声明在 api 层而非 `types/api.ts`：该文件由 T5 独占，本轮不并发改写。
 */
export interface MeetingCreateRequest extends MeetingRequest {
  items?: DecisionItemRequest[]
}

/** 转任务请求（r7 / AC-9）：一次批量统一挂主题与计划开始 */
export interface ConvertPayload {
  itemIds: number[]
  themeId?: number | null
  plannedStartDate?: string | null
}

export interface MeetingQuery {
  /** 后端是按 archived 精确过滤：false = 进行中，true = 已归档视图 */
  archived?: boolean
  keyword?: string
  page?: number
  size?: number
}

export const meetingApi = {
  list(query: MeetingQuery = {}): Promise<PageResponse<MeetingRow>> {
    const params: Record<string, string | number> = {
      archived: query.archived ? 'true' : 'false',
      page: query.page ?? 1,
      size: query.size ?? 20,
    }
    if (query.keyword?.trim()) params.keyword = query.keyword.trim()
    return apiGet<PageResponse<MeetingRow>>('/meetings', { params })
  },

  get(id: number): Promise<MeetingDetail> {
    return apiGet<MeetingDetail>(`/meetings/${id}`)
  },

  create(req: MeetingCreateRequest): Promise<MeetingDetail> {
    return apiPost<MeetingDetail>('/meetings', req)
  },

  /**
   * 该会议已转出的任务（AC-4）：进度区的 4 个计数只从这一个数组算出，
   * 不再读列表页的 taskCount / unfinishedCount，避免两套口径。
   */
  tasks(id: number): Promise<TaskRow[]> {
    return apiGet<TaskRow[]>(`/meetings/${id}/tasks`)
  },

  update(id: number, req: MeetingRequest): Promise<MeetingDetail> {
    return apiPut<MeetingDetail>(`/meetings/${id}`, req)
  },

  /** 幂等：重复归档不报错（AC-5 会议不删除，只从列表隐藏） */
  archive(id: number): Promise<MeetingDetail> {
    return apiPost<MeetingDetail>(`/meetings/${id}/archive`)
  },

  addItem(meetingId: number, req: DecisionItemRequest): Promise<DecisionItemRow> {
    return apiPost<DecisionItemRow>(`/meetings/${meetingId}/decision-items`, req)
  },

  updateItem(itemId: number, req: DecisionItemRequest): Promise<DecisionItemRow> {
    return apiPut<DecisionItemRow>(`/decision-items/${itemId}`, req)
  },

  deleteItem(itemId: number): Promise<void> {
    return apiDelete<void>(`/decision-items/${itemId}`)
  },

  /** 勾选转任务：部分成功是正常结果，不整批回滚（AC-4） */
  /**
   * 转任务（AC-9）：一次批量统一挂主题/计划开始，后端逐条写入后再返回，
   * 主题停用或 plannedStart > 某条 dueDate 时整体 422，不会只转一半。
   */
  convert(meetingId: number, payload: ConvertPayload): Promise<ConvertResult> {
    return apiPost<ConvertResult>(`/meetings/${meetingId}/convert`, payload)
  },
}

/** 转任务结果文案（AC-4）：created / skipped 都要说清楚，用户才知道为何少了一条 */
export function convertSummary(result: ConvertResult): string {
  const created = Array.isArray(result.created) ? result.created.length : 0
  const skipped = Array.isArray(result.skipped) ? result.skipped.length : 0
  const parts: string[] = []
  if (created > 0) parts.push(`已生成 ${created} 条任务`)
  if (skipped > 0) parts.push(`${skipped} 项此前已转任务已跳过`)
  if (parts.length === 0) return '没有可转换的决议事项'
  return parts.join('，')
}
