/**
 * 用户管理接口（TECHNICAL §5.2，全部 admin）。
 * 角色/状态由管理员手工维护——钉钉只提供身份，不提供角色（AC-18）。
 */
import { apiGet, apiPatch, apiPost } from './http'
import type { PageResponse, UserOption, UserPageQuery, UserRow } from '@/types/api'
import type { Role, UserStatus } from '@/types/enums'

export const usersApi = {
  page(query: UserPageQuery = {}): Promise<PageResponse<UserRow>> {
    const params: Record<string, string | number> = {
      page: query.page ?? 1,
      size: query.size ?? 20,
    }
    if (query.keyword) params.keyword = query.keyword
    if (query.role) params.role = query.role
    if (query.status) params.status = query.status
    return apiGet<PageResponse<UserRow>>('/users', { params })
  },

  /** 收件人选人等下拉数据源；默认只给启用账号 */
  options(includeDisabled = false): Promise<UserOption[]> {
    return apiGet<UserOption[]>('/users/options', { params: { includeDisabled } })
  },

  updateRole(id: number, role: Role): Promise<UserRow> {
    return apiPatch<UserRow>(`/users/${id}/role`, { role })
  },

  /** 停用自己 → 409 SELF_DISABLE_FORBIDDEN */
  updateStatus(id: number, status: UserStatus): Promise<UserRow> {
    return apiPatch<UserRow>(`/users/${id}/status`, { status })
  },

  /** 返回一次性临时密码（12 位），后端同时置 must_change_password=1 */
  resetPassword(id: number): Promise<{ temporaryPassword: string }> {
    return apiPost<{ temporaryPassword: string }>(`/users/${id}/password-reset`)
  },
}
