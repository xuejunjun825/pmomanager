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
} from '@/types/api'

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

  create(req: MeetingRequest): Promise<MeetingDetail> {
    return apiPost<MeetingDetail>('/meetings', req)
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
  convert(meetingId: number, itemIds: number[]): Promise<ConvertResult> {
    return apiPost<ConvertResult>(`/meetings/${meetingId}/convert`, { itemIds })
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
