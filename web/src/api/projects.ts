/**
 * 项目配置接口（TECHNICAL §3.5）：/api/projects 五个端点，仅 ADMIN 可写。
 * 与 `sourceTypesApi` 同形：页面不直接拼 URL，错误统一走 http.ts 的 ApiError。
 */
import { apiDelete, apiGet, apiPatch, apiPost, apiPut } from './http'
import type { ProjectPayload, ProjectRow } from '@/types/api'

function path(id: number): string {
  return `/projects/${encodeURIComponent(String(id))}`
}

export const projectsApi = {
  /** 仅 ADMIN；行内含 usageCount，用于引用护栏与删除按钮可见性 */
  list(): Promise<ProjectRow[]> {
    return apiGet<ProjectRow[]>('/projects')
  },

  /** 编码由服务端生成（P%05d），请求体只带名称 / 排序 / 启用 */
  create(payload: ProjectPayload): Promise<ProjectRow> {
    return apiPost<ProjectRow>('/projects', payload)
  },

  update(id: number, payload: ProjectPayload): Promise<ProjectRow> {
    return apiPut<ProjectRow>(path(id), payload)
  },

  /** 行内启停：与用户 / 来源同形状 `{ enabled }`，不回传全部字段 */
  setEnabled(id: number, enabled: boolean): Promise<ProjectRow> {
    return apiPatch<ProjectRow>(`${path(id)}/status`, { enabled })
  },

  /** 204 No Content；被任务引用（PROJECT_IN_USE）或不存在（PROJECT_INVALID）由服务端 409/400 */
  async remove(id: number): Promise<void> {
    await apiDelete<void>(path(id))
  },
}
