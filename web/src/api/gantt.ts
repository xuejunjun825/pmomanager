/**
 * 甘特聚合接口（TECHNICAL §4.2）：一个只读接口返回整棵「主题 → 项目 → 任务」树。
 * 窗口裁剪、逾期 pin、300 截断、主题/项目分组都在服务端完成；前端只把日期换算成像素。
 */
import { apiGet } from './http'
import type { GanttView } from '@/types/api'

export interface GanttQuery {
  /** YYYY-MM-DD（闭区间） */
  from: string
  to: string
  ownerId?: number | ''
  includeDone: boolean
  onlyRisk: boolean
  /** `'<id>'` | `'none'` | `''` */
  theme?: string
}

export const ganttApi = {
  load(q: GanttQuery): Promise<GanttView> {
    const params: Record<string, string | number | boolean> = {
      from: q.from,
      to: q.to,
      includeDone: q.includeDone,
      onlyRisk: q.onlyRisk,
    }
    if (q.ownerId !== undefined && q.ownerId !== '' && q.ownerId !== null) {
      params.ownerId = q.ownerId
    }
    if (q.theme) params.theme = q.theme
    return apiGet<GanttView>('/gantt', { params })
  },
}
