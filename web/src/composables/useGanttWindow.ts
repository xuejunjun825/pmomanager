/**
 * 甘特窗口的日期 → 像素算术（DESIGN §3.1 / TECHNICAL §4.4）。
 *
 * 这一个文件是前端唯一允许做日期算术的地方：窗口生成、翻屏、bar 几何、刻度、
 * 月带全部是纯函数，输入输出只有 `yyyy-MM-dd` 字符串与像素。
 * 它不判断逾期、也不和「今天」比较——`isOverdue / overdueDays / isAtRisk` 只来自接口
 * （PRD AC-7：甘特组件内不得出现基于 dueDate 的日期比较）。
 */
import type { GanttTask, GanttZoom } from '@/types/api'

export const DAY_MS = 86_400_000

/** 窗口：闭区间 [from, to]，含首尾两天 */
export interface GanttWindow {
  from: string
  to: string
  zoom: GanttZoom
}

export interface GanttTick {
  /** 唯一键 + 无障碍标签，等于该刻度起点日期（yyyy-MM-dd） */
  key: string
  /** 刻度文字；抽稀后为 `''`（宽度仍保留整段） */
  label: string
  width: number
  /** 日档周末列底色 */
  weekend: boolean
  /** 是否落在月初（日档画重一点的分隔线） */
  monthStart: boolean
}

export interface GanttMonthBand {
  label: string
  width: number
}

const ZOOM_DAYS: Record<GanttZoom, number> = { day: 14, week: 84, month: 182 }
/** 左侧 sticky 列宽：页面与图表共用这一处常量，禁止再写字面量 280 */
export const LEFT_COLUMN = 280
/** 日宽上限：只有超宽屏才会触发（timelineW > 84×96） */
export const MAX_DAY_WIDTH = 96
const MIN_DAY_WIDTH = 1
/** 容器尚未测量时的保守日宽：宁可首帧窄一点，也绝不先撑出横滚（AC-11） */
export const UNMEASURED_DAY_WIDTH: Record<GanttZoom, number> = { day: 40, week: 10, month: 5 }
const MIN_BAR_WIDTH = 10
/** 刻度窄于此宽就隔一个标一次，避免日号糊成一片 */
const THIN_LABEL_WIDTH = 56

export function parseDay(value: string): number {
  const [y, m, d] = value.split('-').map(Number)
  return Date.UTC(y, (m || 1) - 1, d || 1)
}

export function formatDay(ms: number): string {
  const date = new Date(ms)
  const month = `${date.getUTCMonth() + 1}`.padStart(2, '0')
  const day = `${date.getUTCDate()}`.padStart(2, '0')
  return `${date.getUTCFullYear()}-${month}-${day}`
}

/** 工具提示用短日期：`2026-11-12` → `11-12` */
export function shortDate(value: string): string {
  const parts = value.split('-')
  return parts.length === 3 ? `${parts[1]}-${parts[2]}` : value
}

export function todayString(now: Date = new Date()): string {
  const month = `${now.getMonth() + 1}`.padStart(2, '0')
  const day = `${now.getDate()}`.padStart(2, '0')
  return `${now.getFullYear()}-${month}-${day}`
}

function startOfMonth(ms: number): number {
  const d = new Date(ms)
  return Date.UTC(d.getUTCFullYear(), d.getUTCMonth(), 1)
}

function addMonths(ms: number, offset: number): number {
  const d = new Date(ms)
  return Date.UTC(d.getUTCFullYear(), d.getUTCMonth() + offset, 1)
}

function daysInMonth(ms: number): number {
  const d = new Date(ms)
  return new Date(Date.UTC(d.getUTCFullYear(), d.getUTCMonth() + 1, 0)).getUTCDate()
}

/** 周一为一周起点 */
function startOfWeek(ms: number): number {
  const d = new Date(ms)
  const weekday = (d.getUTCDay() + 6) % 7
  return ms - weekday * DAY_MS
}

/**
 * 三档默认窗口（AC-10）：
 * - 日：今天前 1 天起 14 天
 * - 周：今天所在周的周一往前 2 周起 12 周（默认档）
 * - 月：本月 1 日往前 1 个月起 6 个月
 */
export function defaultWindow(zoom: GanttZoom, today: string): GanttWindow {
  const base = parseDay(today)
  if (zoom === 'day') {
    return { from: formatDay(base - DAY_MS), to: formatDay(base + 12 * DAY_MS), zoom }
  }
  if (zoom === 'month') {
    const from = addMonths(startOfMonth(base), -1)
    const to = addMonths(from, 6) - DAY_MS
    return { from: formatDay(from), to: formatDay(to), zoom }
  }
  const from = startOfWeek(base) - 14 * DAY_MS
  return { from: formatDay(from), to: formatDay(from + 83 * DAY_MS), zoom }
}

/** 整屏平移：一屏 = 窗口自身天数（日 14 / 周 84 / 月 182） */
export function shiftWindow(win: GanttWindow, direction: -1 | 1): GanttWindow {
  const offset = windowDays(win) * direction * DAY_MS
  return {
    from: formatDay(parseDay(win.from) + offset),
    to: formatDay(parseDay(win.to) + offset),
    zoom: win.zoom,
  }
}

export function windowDays(win: GanttWindow): number {
  return Math.round((parseDay(win.to) - parseDay(win.from)) / DAY_MS) + 1
}

export function zoomDays(zoom: GanttZoom): number {
  return ZOOM_DAYS[zoom]
}

/**
 * 日宽（AC-1/2/3）：`max(1, min(96, floor(timelineW / 天数)))`。
 *
 * 入参是**时间轴可用宽**（= `.gantt__scroll` 的 clientWidth − `LEFT_COLUMN`），
 * 保证 `LEFT_COLUMN + 天数 × dayW ≤ 滚动视口宽`，三档都整屏落位、不横滚。
 * 容器未测量（≤ 0）时返回档位保守值，首帧只会「变宽」不会「先超宽再回缩」。
 */
export function dayWidth(win: GanttWindow, timelineWidth: number): number {
  const days = windowDays(win) || ZOOM_DAYS[win.zoom]
  if (!Number.isFinite(timelineWidth) || timelineWidth <= 0) return UNMEASURED_DAY_WIDTH[win.zoom]
  return Math.max(MIN_DAY_WIDTH, Math.min(MAX_DAY_WIDTH, Math.floor(timelineWidth / days)))
}

/** 任务条几何：起点 startDate、终点 dueDate，同一天算 1 天宽；全窗外时贴边保底 10px */
export function barGeometry(
  task: Pick<GanttTask, 'startDate' | 'dueDate' | 'pinnedStart'>,
  win: GanttWindow,
  dayW: number,
): { x: number; width: number } {
  const fromMs = parseDay(win.from)
  const toMs = parseDay(win.to)
  const totalWidth = windowDays(win) * dayW
  const startMs = Math.max(parseDay(task.startDate), fromMs)
  const endMs = Math.min(parseDay(task.dueDate), toMs)
  const spanDays = Math.max(1, Math.round((endMs - startMs) / DAY_MS) + 1)
  const width = Math.max(MIN_BAR_WIDTH, spanDays * dayW)
  if (task.pinnedStart) return { x: 0, width: Math.min(width, totalWidth) }
  const x = Math.max(0, Math.min(totalWidth - width, ((startMs - fromMs) / DAY_MS) * dayW))
  return { x: Math.max(0, x), width }
}

/** 某一天在时间轴上的 x（窗口外返回 -1，供「今天线」与定位判断用） */
export function dayOffset(day: string, win: GanttWindow, dayW: number): number {
  const ms = parseDay(day)
  if (ms < parseDay(win.from) || ms > parseDay(win.to)) return -1
  return ((ms - parseDay(win.from)) / DAY_MS) * dayW
}

/**
 * 刻度（表头第二行）：
 * - 日档：每天一格，周末标 weekend，月初标 monthStart
 * - 周档：每周一格（周一），label 为周一日期
 * - 月档：每个自然月一格，label 为 `M月`（窗口被翻屏切到月中时首尾按天数裁）
 * 像素宽度不足 56 时隔一个标一次，格宽不变。
 */
export function buildTicks(win: GanttWindow, dayW: number): GanttTick[] {
  const fromMs = parseDay(win.from)
  const toMs = parseDay(win.to)
  if (win.zoom === 'day') {
    const ticks: GanttTick[] = []
    const thin = dayW < THIN_LABEL_WIDTH
    for (let ms = fromMs; ms <= toMs; ms += DAY_MS) {
      const d = new Date(ms)
      const index = Math.round((ms - fromMs) / DAY_MS)
      ticks.push({
        key: formatDay(ms),
        label: thin && index % 2 === 1 ? '' : `${`${d.getUTCMonth() + 1}`.padStart(2, '0')}/${`${d.getUTCDate()}`.padStart(2, '0')}`,
        width: dayW,
        weekend: d.getUTCDay() === 0 || d.getUTCDay() === 6,
        monthStart: d.getUTCDate() === 1,
      })
    }
    return ticks
  }

  if (win.zoom === 'week') {
    const ticks: GanttTick[] = []
    const weekWidth = dayW * 7
    const thin = weekWidth < THIN_LABEL_WIDTH
    let cursor = fromMs
    let index = 0
    while (cursor <= toMs) {
      // 一周 = 周一 00:00 起 7 个整天；这里始终用 00:00 的日界，避免 -1ms 漂移
      const end = Math.min(cursor + 6 * DAY_MS, toMs)
      ticks.push({
        key: formatDay(cursor),
        label: thin && index % 2 === 1 ? '' : monthDayLabel(cursor),
        width: ((end - cursor) / DAY_MS + 1) * dayW,
        weekend: false,
        monthStart: false,
      })
      cursor = end + DAY_MS
      index += 1
    }
    return ticks
  }

  const ticks: GanttTick[] = []
  let cursor = fromMs
  while (cursor <= toMs) {
    const monthStart = startOfMonth(cursor)
    const monthEnd = addMonths(monthStart, 1) - DAY_MS
    const end = Math.min(monthEnd, toMs)
    ticks.push({
      key: formatDay(cursor),
      label: `${new Date(cursor).getUTCMonth() + 1}月`,
      width: ((end - cursor) / DAY_MS + 1) * dayW,
      weekend: false,
      monthStart: true,
    })
    cursor = end + DAY_MS
  }
  return ticks
}

function monthDayLabel(ms: number): string {
  const d = new Date(ms)
  return `${`${d.getUTCMonth() + 1}`.padStart(2, '0')}/${`${d.getUTCDate()}`.padStart(2, '0')}`
}

/**
 * 月带（表头第一行）：日/周档按自然月标「YYYY年M月」；月档按年标「YYYY年」。
 * 宽度按窗口内实际天数裁，翻屏切到月中也不会错位。
 */
export function monthBands(win: GanttWindow, dayW: number): GanttMonthBand[] {
  const fromMs = parseDay(win.from)
  const toMs = parseDay(win.to)
  const bands: GanttMonthBand[] = []
  if (win.zoom === 'month') {
    let cursor = fromMs
    while (cursor <= toMs) {
      const d = new Date(cursor)
      const yearEnd = Date.UTC(d.getUTCFullYear() + 1, 0, 1) - DAY_MS
      const end = Math.min(yearEnd, toMs)
      bands.push({
        label: `${d.getUTCFullYear()}年`,
        width: ((end - cursor) / DAY_MS + 1) * dayW,
      })
      cursor = end + DAY_MS
    }
    return bands
  }
  let cursor = fromMs
  while (cursor <= toMs) {
    const d = new Date(cursor)
    const nextMonth = cursor + (daysInMonth(cursor) - d.getUTCDate() + 1) * DAY_MS
    const end = Math.min(nextMonth - DAY_MS, toMs)
    bands.push({
      label: `${d.getUTCFullYear()}年${d.getUTCMonth() + 1}月`,
      width: ((end - cursor) / DAY_MS + 1) * dayW,
    })
    cursor = end + DAY_MS
  }
  return bands
}
