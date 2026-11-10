/**
 * 「来源类型配置 → 下拉选项」的唯一投影（r3 收口，替代四处逐字复制的 computed）。
 *
 * 只依赖 `@/types/api` 的类型：不 import 任何 `.vue`，也不依赖 `AppSelect` 的落点，
 * 结构上自带 `SourceOption` 供 `AppSelect` 的 `options` 吸收（单向依赖：types → utils → 页面/组件）。
 */
import type { SourceTypeOption } from '@/types/api'

/** 结构兼容 `AppSelect` 的 `SelectOption`；不反向 import 组件层类型 */
export interface SourceOption {
  label: string
  value: string
}

export interface SourceOptionsConfig {
  /** 是否在首位插入「全部来源」占位（默认 true：筛选场景） */
  allOption?: boolean
  /** 是否保留停用来源并加「（已停用）」（默认 true：筛选需能筛到历史任务） */
  includeDisabled?: boolean
}

const DISABLED_SUFFIX = '（已停用）'

/** 唯一比较器：启用优先，同组内按 sort 升序 */
function byEnabledThenSort(a: SourceTypeOption, b: SourceTypeOption): number {
  if (a.enabled !== b.enabled) return a.enabled ? -1 : 1
  return a.sort - b.sort
}

/**
 * 把来源类型配置投影为下拉选项。
 * 绝不就地排序入参：先 `slice()`/`filter()` 复制再排序，否则跨页顺序会随渲染漂移。
 */
export function sourceOptions(
  types: SourceTypeOption[] | null | undefined,
  config: SourceOptionsConfig = {},
): SourceOption[] {
  const allOption = config.allOption ?? true
  const includeDisabled = config.includeDisabled ?? true

  const all = Array.isArray(types) ? types : []
  const visible = includeDisabled ? all.slice() : all.filter((item) => item.enabled !== false)
  const ordered = visible.sort(byEnabledThenSort)

  const options: SourceOption[] = ordered.map((item) => ({
    label: item.enabled === false ? `${item.label}${DISABLED_SUFFIX}` : item.label,
    value: item.value,
  }))

  return allOption ? [{ label: '全部来源', value: '' }, ...options] : options
}
