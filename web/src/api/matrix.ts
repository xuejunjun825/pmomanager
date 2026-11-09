/**
 * 闭环矩阵接口（AC-15 / AC-17）。
 * 矩阵的五个数字列互斥：逾期任务已从原状态列移出，所以下钻必须带上 overdue=false，
 * 否则任务池的「待开始」会把逾期的那部分也数进来，点击后的数字就对不上了。
 */
import type { LocationQueryRaw } from 'vue-router'
import { apiGet } from './http'
import type { MatrixQuery, MatrixView, MatrixRow } from '@/types/api'

export type MatrixBucketKey = 'pending' | 'inProgress' | 'pendingAccept' | 'overdue' | 'done' | 'cancelled'

export interface MatrixBucket {
  key: MatrixBucketKey
  label: string
  /** 逾期列是唯一允许红底的一格 */
  danger?: boolean
}

export const MATRIX_BUCKETS: MatrixBucket[] = [
  { key: 'pending', label: '待开始' },
  { key: 'inProgress', label: '进行中' },
  { key: 'pendingAccept', label: '待验收' },
  { key: 'overdue', label: '逾期', danger: true },
  { key: 'done', label: '已完成' },
]

export interface DrillFilter {
  sourceType?: string
}

export interface DrillTarget {
  path: string
  query: LocationQueryRaw
}

/**
 * 单元格 → 任务池筛选参数（AC-15「下钻参数与格口径一致」）。
 * 纯函数，单测直接钉住每一列的参数，避免以后有人漏掉 overdue=false。
 */
export function matrixDrilldown(
  row: Pick<MatrixRow, 'userId'>,
  bucket: MatrixBucketKey,
  filter: DrillFilter = {},
): DrillTarget {
  const query: Record<string, string> = { owner: String(row.userId) }
  switch (bucket) {
    case 'pending':
      query.status = 'PENDING'
      query.overdue = 'false'
      break
    case 'inProgress':
      query.status = 'IN_PROGRESS'
      query.overdue = 'false'
      break
    case 'pendingAccept':
      query.status = 'PENDING_ACCEPT'
      query.overdue = 'false'
      break
    case 'overdue':
      query.overdue = 'true'
      break
    case 'done':
      query.status = 'DONE'
      break
    case 'cancelled':
      query.status = 'CANCELLED'
      break
  }
  if (filter.sourceType) query.source = filter.sourceType
  return { path: '/tasks', query }
}

export const matrixApi = {
  load(query: MatrixQuery = {}): Promise<MatrixView> {
    const params: Record<string, string> = { range: query.range ?? 'ALL' }
    if (query.sourceType) params.sourceType = query.sourceType
    if (typeof query.includeDisabled === 'boolean') params.includeDisabled = String(query.includeDisabled)
    return apiGet<MatrixView>('/matrix', { params })
  },
}
