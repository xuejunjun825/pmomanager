/** 筛选下拉用的元数据（TECHNICAL §5.5 /api/meta/options） */
import { apiGet } from './http'
import type { MetaOptions } from '@/types/api'

export const metaApi = {
  options(): Promise<MetaOptions> {
    return apiGet<MetaOptions>('/meta/options')
  },
}
