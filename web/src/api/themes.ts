/**
 * 主题配置接口（TECHNICAL §4.2）：/api/themes 六个端点，写操作仅 ADMIN。
 * 与 `projectsApi` 同形：页面不直接拼 URL，错误统一走 http.ts 的 ApiError。
 */
import { apiDelete, apiGet, apiPatch, apiPost, apiPut } from './http'
import type { SourcePaletteEntry, ThemePayload, ThemeRow } from '@/types/api'

function path(id: number): string {
  return `/themes/${encodeURIComponent(String(id))}`
}

export const themesApi = {
  /** 仅 ADMIN；行内含 usageCount，用于引用护栏与删除按钮可见性 */
  list(): Promise<ThemeRow[]> {
    return apiGet<ThemeRow[]>('/themes')
  },

  /** 8 色色板（复用来源色块），弹窗打开时按需拉取 */
  palette(): Promise<SourcePaletteEntry[]> {
    return apiGet<SourcePaletteEntry[]>('/themes/palette')
  },

  /** 名称全表唯一，服务端重名 409 THEME_NAME_DUPLICATE */
  create(payload: ThemePayload): Promise<ThemeRow> {
    return apiPost<ThemeRow>('/themes', payload)
  },

  update(id: number, payload: ThemePayload): Promise<ThemeRow> {
    return apiPut<ThemeRow>(path(id), payload)
  },

  /** 行内启停：与用户 / 来源 / 项目同形状 `{ enabled }` */
  setEnabled(id: number, enabled: boolean): Promise<ThemeRow> {
    return apiPatch<ThemeRow>(`${path(id)}/status`, { enabled })
  },

  /** 204 No Content；被任务引用（THEME_IN_USE）或不存在（THEME_INVALID）由服务端 409/400 */
  async remove(id: number): Promise<void> {
    await apiDelete<void>(path(id))
  },
}
