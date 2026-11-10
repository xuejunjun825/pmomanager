/**
 * 来源配置接口（TECHNICAL §5.2）：/api/source-types 六个端点。
 * 编码是后端生成的业务主键，前端只在路径里原样回传（编码可能含字母数字，仍统一转义）。
 */
import { apiDelete, apiGet, apiPatch, apiPost, apiPut } from './http'
import type {
  SourcePaletteEntry,
  SourceTypePayload,
  SourceTypeRow,
} from '@/types/api'

function path(code: string): string {
  return `/source-types/${encodeURIComponent(code)}`
}

export const sourceTypesApi = {
  list(): Promise<SourceTypeRow[]> {
    return apiGet<SourceTypeRow[]>('/source-types')
  },

  palette(): Promise<SourcePaletteEntry[]> {
    return apiGet<SourcePaletteEntry[]>('/source-types/palette')
  },

  create(payload: SourceTypePayload): Promise<SourceTypeRow> {
    return apiPost<SourceTypeRow>('/source-types', payload)
  },

  update(code: string, payload: SourceTypePayload): Promise<SourceTypeRow> {
    return apiPut<SourceTypeRow>(path(code), payload)
  },

  /** 与用户启停同形状：{ enabled }，前端复用同一套交互 */
  setEnabled(code: string, enabled: boolean): Promise<SourceTypeRow> {
    return apiPatch<SourceTypeRow>(`${path(code)}/status`, { enabled })
  },

  /** 204 No Content；被引用（SOURCE_IN_USE）或内置（SOURCE_BUILTIN_PROTECTED）由服务端 409 */
  async remove(code: string): Promise<void> {
    await apiDelete<void>(path(code))
  },
}
