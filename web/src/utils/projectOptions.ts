/**
 * 项目 → 下拉选项 / 任务行项目位 的唯一投影（r4，镜像 r3 `sourceOptions` 的形态）。
 *
 * 三处筛选（任务池 / 矩阵 / 看板）必须调用同一个 `projectOptions`；表格 / 详情 / 看板的
 * 「是否渲染项目标签、是否未指定」必须走同一个 `projectView`，否则三处合计必然漂移。
 *
 * 只依赖 `@/types/api`：不 import `.vue`，不依赖任何页面；`ProjectOption` 结构自带，
 * 供 `AppSearchSelect` 的 `options` 直接吸收（单向依赖：types → utils → 页面/组件）。
 */
import type { ProjectOptionRow, TaskSource } from '@/types/api'

/** 结构兼容 `AppSearchSelect` 的选项；不反向 import 组件层类型 */
export interface ProjectOption {
  label: string
  value: string
}

export interface ProjectOptionsConfig {
  /** 首位插入「全部项目」（筛选场景，默认 true） */
  allOption?: boolean
  /** 保留停用项目并加「（已停用）」（筛选场景默认 true；建任务抽屉传 false） */
  includeDisabled?: boolean
  /** 末尾追加「未指定项目」（只允许筛选与改形历史，默认 false） */
  includeUnassigned?: boolean
}

/** 「全部项目」哨兵值：与数字 id 字符串、`none` 都不冲突 */
export const UNASSIGNED_VALUE = 'none'
export const DISABLED_SUFFIX = '（已停用）'

const ALL_LABEL = '全部项目'
const UNASSIGNED_LABEL = '未指定项目'

/** 唯一比较器：启用在前 → sort 升序 → id 升序（同 sort 时顺序稳定，跨页一致） */
function byEnabledThenSort(a: ProjectOptionRow, b: ProjectOptionRow): number {
  if (a.enabled !== b.enabled) return a.enabled ? -1 : 1
  return (a.sort ?? 0) - (b.sort ?? 0) || a.id - b.id
}

/**
 * 把项目主数据投影为下拉选项。
 * 绝不就地排序入参：先 `slice()` / `filter()` 复制，否则跨页顺序会随渲染漂移。
 * 任何选项都不带编码（`P00012` 永不出现在下拉里）。
 */
export function projectOptions(
  projects: ProjectOptionRow[] | null | undefined,
  config: ProjectOptionsConfig = {},
): ProjectOption[] {
  const allOption = config.allOption ?? true
  const includeDisabled = config.includeDisabled ?? true
  const includeUnassigned = config.includeUnassigned ?? false

  const all = Array.isArray(projects) ? projects : []
  const visible = includeDisabled ? all.slice() : all.filter((item) => item.enabled !== false)
  const ordered = visible.sort(byEnabledThenSort)

  const options: ProjectOption[] = ordered.map((item) => ({
    label: item.enabled === false ? `${item.name}${DISABLED_SUFFIX}` : item.name,
    value: String(item.id),
  }))

  if (allOption) options.unshift({ label: ALL_LABEL, value: '' })
  if (includeUnassigned) options.push({ label: UNASSIGNED_LABEL, value: UNASSIGNED_VALUE })
  return options
}

/** 任务来源的「项目位」展示决策：null = 不渲染（表格 / 详情 / 看板共用） */
export interface ProjectView {
  /** 项目名称；`unspecified` 时为空串 */
  name: string
  /** 仅 `projectEnabled === false` 才是停用；null（未知）按启用显示 */
  enabled: boolean
  /** 改形历史：形态是「需绑定项目」但任务没有项目 */
  unspecified: boolean
}

/**
 * 唯一判定点：
 * 1) 有项目 → 名称 + 停用后缀；
 * 2) 无项目但形态是 PROJECT → 「未指定项目」档；
 * 3) 其他（会议 / 说明 / 老接口无 form）→ 不渲染任何项目元素。
 */
export function projectView(source: TaskSource | null | undefined): ProjectView | null {
  if (!source) return null
  if (source.projectId != null) {
    return {
      name: source.projectName ?? '',
      enabled: source.projectEnabled !== false,
      unspecified: false,
    }
  }
  if (source.form === 'PROJECT') {
    return { name: '', enabled: true, unspecified: true }
  }
  return null
}
