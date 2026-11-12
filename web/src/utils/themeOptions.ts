/**
 * 主题 → 下拉选项 / 任务行主题位 的唯一投影（r7，镜像 `projectOptions` 的形态）。
 *
 * 任务表单、任务池筛选、甘特筛选必须调用同一个 `themeOptions`；`ThemeTag` 的展示兜底走
 * `themeView`。三色一律取后端派生值，前端不解析 hex，也不新增第二套主题色令牌。
 *
 * 只依赖 `@/types/api` 与 `projectOptions` 的哨兵值：不 import `.vue`，不依赖页面。
 */
import { DISABLED_SUFFIX, UNASSIGNED_VALUE } from './projectOptions'
import type { ThemeOptionRow, ThemeRef } from '@/types/api'

/** 结构兼容 `AppSelect` / `AppSearchSelect` 的选项；不反向 import 组件层类型 */
export interface ThemeOption {
  label: string
  value: string
  disabled?: boolean
}

export interface ThemeOptionsConfig {
  /** 首位插入「全部主题」（筛选场景，默认 true） */
  allOption?: boolean
  /** 保留停用主题并加「（已停用）」并置灰（筛选场景默认 true；建任务抽屉传 false） */
  includeDisabled?: boolean
  /** 末尾追加「未挂主题」（筛选与任务表单默认 false） */
  includeUnassigned?: boolean
}

const ALL_LABEL = '全部主题'
const UNASSIGNED_LABEL = '未挂主题'

/** 唯一比较器：启用在前 → sort 升序 → id 升序（同 sort 时稳定，跨页一致） */
function byEnabledThenSort(a: ThemeOptionRow, b: ThemeOptionRow): number {
  if (a.enabled !== b.enabled) return a.enabled ? -1 : 1
  return (a.sort ?? 0) - (b.sort ?? 0) || a.id - b.id
}

/** 把主题主数据投影为下拉选项；绝不就地排序入参（先复制，跨页顺序不漂移） */
export function themeOptions(
  themes: ThemeOptionRow[] | null | undefined,
  config: ThemeOptionsConfig = {},
): ThemeOption[] {
  const allOption = config.allOption ?? true
  const includeDisabled = config.includeDisabled ?? true
  const includeUnassigned = config.includeUnassigned ?? false

  const all = Array.isArray(themes) ? themes : []
  const visible = includeDisabled ? all.slice() : all.filter((item) => item.enabled !== false)
  const ordered = visible.sort(byEnabledThenSort)

  const options: ThemeOption[] = ordered.map((item) => ({
    label: item.enabled === false ? `${item.name}${DISABLED_SUFFIX}` : item.name,
    value: String(item.id),
    disabled: item.enabled === false,
  }))

  if (allOption) options.unshift({ label: ALL_LABEL, value: '' })
  if (includeUnassigned) options.push({ label: UNASSIGNED_LABEL, value: UNASSIGNED_VALUE })
  return options
}

/** 任务行「主题位」展示决策：null / 无名称 = 不渲染；三色原样透传给 `ThemeTag` */
export interface ThemeView {
  name: string
  color: string | null
  softColor: string | null
  borderColor: string | null
  enabled: boolean
  /** 未挂主题（历史任务）；调用方据此决定不渲染标签 */
  unspecified: boolean
}

const EMPTY_THEME: ThemeView = {
  name: '',
  color: null,
  softColor: null,
  borderColor: null,
  enabled: true,
  unspecified: true,
}

/**
 * 唯一判定点：
 * 1) 有主题 → 名称 + 三色；`enabled === false` 时标签加「（已停用）」后缀；
 * 2) 无主题（null / undefined / 空名）→ `unspecified=true`，调用方不渲染标签。
 */
export function themeView(theme: ThemeRef | null | undefined): ThemeView {
  const name = theme?.name?.trim() ?? ''
  if (!theme || !name) return { ...EMPTY_THEME }
  return {
    name,
    color: theme.color || null,
    softColor: theme.softColor || null,
    borderColor: theme.borderColor || null,
    enabled: theme.enabled !== false,
    unspecified: false,
  }
}
