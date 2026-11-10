/**
 * 管理层看板接口（AC-16 / AC-17）。
 * 6 项 KPI、状态构成与督办焦点由同一次请求返回，保证三块数字同源；
 * 逾期与否是服务端算的，前端不做任何日期推算。
 */
import { apiGet } from './http'
import type { DashboardQuery, DashboardView } from '@/types/api'

export const dashboardApi = {
  load(query: DashboardQuery = {}): Promise<DashboardView> {
    const params: Record<string, string | number> = {}
    if (query.ownerId !== undefined && query.ownerId !== '') params.ownerId = query.ownerId
    if (query.sourceType) params.sourceType = query.sourceType
    // 与任务池/矩阵同名同义（AC-5）：后端同一个 ProjectFilterSql，前端不拼条件
    if (query.project) params.project = query.project
    if (query.meetingId !== undefined && query.meetingId !== '') params.meetingId = query.meetingId
    params.focusLimit = query.focusLimit ?? 10
    return apiGet<DashboardView>('/dashboard', { params })
  },
}
