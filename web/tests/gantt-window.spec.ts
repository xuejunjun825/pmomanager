import { describe, expect, it } from 'vitest'
import { readFileSync, readdirSync } from 'node:fs'
import { resolve } from 'node:path'
import {
  barGeometry,
  buildTicks,
  dayOffset,
  dayWidth,
  defaultWindow,
  formatDay,
  monthBands,
  parseDay,
  shiftWindow,
  windowDays,
} from '@/composables/useGanttWindow'
import type { GanttTask } from '@/types/api'

const TODAY = '2026-11-12' // 周四

function task(overrides: Partial<GanttTask> = {}): GanttTask {
  return {
    id: 1,
    title: '任务',
    owner: { id: 2, name: '张三' },
    status: 'IN_PROGRESS',
    statusLabel: '进行中',
    startDate: '2026-11-01',
    plannedStartDate: null,
    dueDate: '2026-11-20',
    estimatedStart: false,
    isOverdue: false,
    overdueDays: 0,
    isAtRisk: false,
    pinnedStart: false,
    pinnedEnd: false,
    projectId: 1,
    projectName: '项目',
    sourceLabel: '会议',
    sourceColor: '#1b4d8f',
    ...overrides,
  }
}

describe('useGanttWindow · 三档默认窗口与翻屏（AC-10）', () => {
  it('日档：今天前 1 天起 14 天', () => {
    const win = defaultWindow('day', TODAY)
    expect(win.from).toBe('2026-11-11')
    expect(win.to).toBe('2026-11-24')
    expect(windowDays(win)).toBe(14)
  })

  it('周档：本周一往前 2 周起 12 周', () => {
    const win = defaultWindow('week', TODAY)
    expect(win.from).toBe('2026-10-26')
    expect(win.to).toBe('2027-01-17')
    expect(windowDays(win)).toBe(84)
  })

  it('月档：本月 1 日往前 1 个月起 6 个月（含跨年）', () => {
    const win = defaultWindow('month', TODAY)
    expect(win.from).toBe('2026-10-01')
    expect(win.to).toBe('2027-03-31')
    expect(windowDays(win)).toBe(182)
  })

  it('翻屏整屏平移，日期与档位都不变', () => {
    const next = shiftWindow(defaultWindow('week', TODAY), 1)
    expect(next.from).toBe('2027-01-18')
    expect(next.to).toBe('2027-04-11')
    expect(next.zoom).toBe('week')
    const prev = shiftWindow(next, -1)
    expect(prev.from).toBe('2026-10-26')
    expect(prev.to).toBe('2027-01-17')
    const month = shiftWindow(defaultWindow('month', TODAY), 1)
    expect(windowDays(month)).toBe(182)
    expect(month.from).toBe('2027-04-01')
    expect(month.to).toBe('2027-09-29')
  })

  it('日期只做 UTC 运算，不引入时区偏移', () => {
    expect(formatDay(parseDay('2026-11-12'))).toBe('2026-11-12')
    expect(formatDay(parseDay('2026-01-01') + 86_400_000)).toBe('2026-01-02')
  })
})

describe('useGanttWindow · dayW clamp 40–96', () => {
  it('容器够宽时放宽到 96 上限', () => {
    expect(dayWidth(defaultWindow('week', TODAY), 84 * 500)).toBe(96)
  })

  it('容器不足时压到 40 下限，不再横滚', () => {
    expect(dayWidth(defaultWindow('day', TODAY), 140)).toBe(40)
    expect(dayWidth(defaultWindow('month', TODAY), 0)).toBe(40)
  })

  it('中间值按 floor(宽度 / 天数) 取整', () => {
    expect(dayWidth(defaultWindow('day', TODAY), 700)).toBe(50)
    expect(dayWidth(defaultWindow('week', TODAY), 84 * 60 + 20)).toBe(60)
  })
})

describe('useGanttWindow · 条几何', () => {
  const win = defaultWindow('day', TODAY)
  const dayW = 48

  it('同一天的任务至少 1 天宽', () => {
    const geometry = barGeometry(task({ startDate: '2026-11-12', dueDate: '2026-11-12' }), win, dayW)
    expect(geometry.width).toBe(dayW)
    expect(geometry.x).toBe(dayW)
  })

  it('跨天按闭区间算天数（11-12 ~ 11-14 = 3 天）', () => {
    const geometry = barGeometry(task({ startDate: '2026-11-12', dueDate: '2026-11-14' }), win, dayW)
    expect(geometry.x).toBe(dayW)
    expect(geometry.width).toBe(dayW * 3)
  })

  it('逾期条贴窗口左边界 x === 0（AC-11）', () => {
    const geometry = barGeometry(
      task({ startDate: '2026-10-01', dueDate: '2026-10-03', pinnedStart: true, isOverdue: true, overdueDays: 40 }),
      win,
      dayW,
    )
    expect(geometry.x).toBe(0)
    expect(geometry.width).toBeGreaterThanOrEqual(10)
  })

  it('右界外的风险条被夹在窗口右缘，不溢出画布', () => {
    const geometry = barGeometry(
      task({ startDate: '2026-11-20', dueDate: '2027-02-01', pinnedEnd: true, isAtRisk: true }),
      win,
      dayW,
    )
    expect(geometry.x + geometry.width).toBeLessThanOrEqual(windowDays(win) * dayW)
    expect(geometry.width).toBeGreaterThan(0)
  })

  it('短条保底 10px，日档最小宽度仍可点中', () => {
    const tiny = barGeometry(task({ startDate: '2026-11-12', dueDate: '2026-11-12' }), win, 4)
    expect(tiny.width).toBe(10)
  })
})

describe('useGanttWindow · 刻度与月带（AC-10）', () => {
  const dayW = 48

  it('日档 14 格、周末标记、月初加粗', () => {
    const ticks = buildTicks(defaultWindow('day', TODAY), dayW)
    expect(ticks).toHaveLength(14)
    expect(ticks[0].label).toBe('11/11')
    expect(ticks[3].weekend).toBe(true) // 11-14 周六
    expect(ticks[4].weekend).toBe(true) // 11-15 周日
    expect(ticks[3].width).toBe(dayW)
    expect(ticks.every((tick) => tick.width === dayW)).toBe(true)
  })

  it('日档窄到 40 时隔天标一次，格宽不变', () => {
    const ticks = buildTicks(defaultWindow('day', TODAY), 40)
    expect(ticks).toHaveLength(14)
    expect(ticks[0].label).toBe('11/11')
    expect(ticks[1].label).toBe('')
    expect(ticks[2].label).not.toBe('')
  })

  it('周档 12 格，每格 7 天，标签是周一', () => {
    const ticks = buildTicks(defaultWindow('week', TODAY), 12)
    expect(ticks).toHaveLength(12)
    expect(ticks.every((tick) => tick.width === 84)).toBe(true)
    expect(ticks[0].key).toBe('2026-10-26')
    expect(ticks[0].label).toBe('10/26')
  })

  it('月档 6 格，标签是 M月，宽度合计等于窗口', () => {
    const win = defaultWindow('month', TODAY)
    const ticks = buildTicks(win, 6)
    expect(ticks).toHaveLength(6)
    expect(ticks.map((tick) => tick.label)).toEqual(['10月', '11月', '12月', '1月', '2月', '3月'])
    const total = ticks.reduce((sum, tick) => sum + tick.width, 0)
    expect(total).toBe(windowDays(win) * 6)
  })

  it('月带：日/周档按自然月，月档按年（含跨年）', () => {
    expect(monthBands(defaultWindow('day', TODAY), dayW).map((band) => band.label)).toEqual(['2026年11月'])
    const weekBands = monthBands(defaultWindow('week', TODAY), 12)
    expect(weekBands[0].label).toBe('2026年10月')
    expect(weekBands.reduce((sum, band) => sum + band.width, 0)).toBe(84 * 12)
    const monthBandsOfYear = monthBands(defaultWindow('month', TODAY), 6)
    expect(monthBandsOfYear.map((band) => band.label)).toEqual(['2026年', '2027年'])
    expect(monthBandsOfYear.reduce((sum, band) => sum + band.width, 0)).toBe(182 * 6)
  })

  it('今天线落在窗口内，窗口外返回 -1', () => {
    const win = defaultWindow('day', TODAY)
    expect(dayOffset(TODAY, win, dayW)).toBe(dayW)
    expect(dayOffset('2026-12-01', win, dayW)).toBe(-1)
    expect(dayOffset('2026-10-01', win, dayW)).toBe(-1)
  })
})

describe('AC-7 · 甘特组件内没有日期推算', () => {
  it('components/gantt 下不使用 Date/new Date/时间戳运算', () => {
    const dir = resolve(process.cwd(), 'src/components/gantt')
    const files = readdirSync(dir).filter((name) => name.endsWith('.vue'))
    expect(files.length).toBeGreaterThan(0)
    for (const file of files) {
      const source = readFileSync(resolve(dir, file), 'utf8')
      expect(source, file).not.toMatch(/new Date\(/)
      expect(source, file).not.toMatch(/Date\.now/)
      expect(source, file).not.toMatch(/getTime\(/)
    }
  })
})
