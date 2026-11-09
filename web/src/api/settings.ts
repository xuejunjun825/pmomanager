/**
 * 系统设置接口（TECHNICAL §5.5，admin）。
 *
 * 保存后**不**信任 PUT 的响应体（契约只约定 200，未约定结构）：
 * 页面重新 GET /settings 取权威状态，避免前后端对响应形状各猜一套。
 */
import { apiGet, apiPut } from './http'
import type { SettingsView, UpdateReportSettingsRequest } from '@/types/api'

export const settingsApi = {
  get(): Promise<SettingsView> {
    return apiGet<SettingsView>('/settings')
  },

  updateReport(payload: UpdateReportSettingsRequest): Promise<void> {
    return apiPut<void>('/settings/report', payload)
  },
}
