/**
 * 甘特会话状态（TECHNICAL §4.4 / AC-11）。
 *
 * 模块级单例：档位、筛选、折叠在「任务详情 → 返回甘特」这类路由往返后仍然保持，
 * 刷新页面才回到默认。折叠键 `theme:<id>` / `project:<themeId>:<projectId>`，
 * 页面只通过 isCollapsed / toggle / toggleTheme 读写，不自己拼第二个键格式。
 */
import { reactive, ref, type Ref } from 'vue'
import type { GanttQuickFilter, GanttZoom } from '@/types/api'

interface GanttViewState {
  zoom: Ref<GanttZoom>
  onlyRisk: Ref<boolean>
  showDone: Ref<boolean>
  filter: Ref<GanttQuickFilter>
  collapsed: Set<string>
  isCollapsed: (key: string) => boolean
  toggle: (key: string) => void
  toggleTheme: (themeId: number | null) => void
  reset: () => void
}

const zoom = ref<GanttZoom>('week')
const onlyRisk = ref(false)
const showDone = ref(true)
const filter = ref<GanttQuickFilter>('')
const collapsed = reactive(new Set<string>())

function isCollapsed(key: string): boolean {
  return collapsed.has(key)
}

function toggle(key: string): void {
  if (collapsed.has(key)) collapsed.delete(key)
  else collapsed.add(key)
}

function toggleTheme(themeId: number | null): void {
  toggle(themeKey(themeId))
}

function reset(): void {
  zoom.value = 'week'
  onlyRisk.value = false
  showDone.value = true
  filter.value = ''
  collapsed.clear()
}

export function themeKey(themeId: number | null): string {
  return `theme:${themeId ?? 'none'}`
}

export function projectKey(themeId: number | null, projectId: number | null): string {
  return `project:${themeId ?? 'none'}:${projectId ?? 'none'}`
}

export function useGanttView(): GanttViewState {
  return { zoom, onlyRisk, showDone, filter, collapsed, isCollapsed, toggle, toggleTheme, reset }
}
