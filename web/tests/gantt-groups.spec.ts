// @vitest-environment jsdom
import { describe, expect, it } from 'vitest'
import { mount } from '@vue/test-utils'
import { ApiError } from '@/api/http'
import GanttChart from '@/components/gantt/GanttChart.vue'
import { defaultWindow } from '@/composables/useGanttWindow'
import { projectKey, themeKey } from '@/composables/useGanttView'
import type { GanttProjectGroup, GanttTask, GanttThemeGroup, GanttView } from '@/types/api'

const TODAY = '2026-11-12'
const window = defaultWindow('week', TODAY)

function task(overrides: Partial<GanttTask> = {}): GanttTask {
  return {
    id: 11,
    title: '补齐季度材料',
    owner: { id: 2, name: '张三' },
    status: 'IN_PROGRESS',
    statusLabel: '进行中',
    startDate: '2026-11-02',
    plannedStartDate: '2026-11-02',
    dueDate: '2026-11-20',
    estimatedStart: false,
    isOverdue: false,
    overdueDays: 0,
    isAtRisk: false,
    pinnedStart: false,
    pinnedEnd: false,
    projectId: 7,
    projectName: '年度审计',
    sourceLabel: '周例会',
    sourceColor: '#1b4d8f',
    ...overrides,
  }
}

function project(overrides: Partial<GanttProjectGroup> = {}): GanttProjectGroup {
  const tasks = overrides.tasks ?? [task()]
  return {
    projectId: 7,
    projectName: '年度审计',
    unspecified: false,
    taskCount: tasks.length,
    tasks,
    ...overrides,
  }
}

function theme(overrides: Partial<GanttThemeGroup> = {}): GanttThemeGroup {
  const projects = overrides.projects ?? [project()]
  return {
    themeId: 3,
    themeName: '合规建设',
    color: '#1b4d8f',
    softColor: '#eef3f9',
    borderColor: '#c7d7e8',
    enabled: true,
    unassigned: false,
    taskCount: projects.reduce((sum, item) => sum + item.taskCount, 0),
    overdueCount: 0,
    riskCount: 0,
    projects,
    ...overrides,
  }
}

const baseView: GanttView = {
  today: TODAY,
  from: window.from,
  to: window.to,
  truncated: false,
  windowTotal: 2,
  summary: { overdue: 1, risk: 1, inProgress: 1, pendingAccept: 0, done: 1, unassigned: 1 },
  groups: [],
}

function mountChart(props: Record<string, unknown> = {}) {
  return mount(GanttChart, {
    props: {
      groups: [theme()],
      window,
      collapsed: new Set<string>(),
      dayW: 12,
      loading: false,
      error: null,
      truncated: false,
      windowTotal: baseView.windowTotal,
      today: TODAY,
      ...props,
    },
  })
}

describe('GanttChart · 主题 / 项目 / 任务三级行（AC-10）', () => {
  it('主题行带引用数与逾期/风险计数，项目行带任务数', () => {
    const wrapper = mountChart({
      groups: [
        theme({
          taskCount: 3,
          overdueCount: 1,
          riskCount: 1,
          projects: [
            project({
              tasks: [
                task({ isOverdue: true, overdueDays: 5, pinnedStart: true }),
                task({ id: 12, status: 'PENDING_ACCEPT', statusLabel: '待验收', isAtRisk: true }),
              ],
              taskCount: 2,
            }),
          ],
        }),
      ],
    })
    const themeRow = wrapper.find('.row--theme')
    expect(themeRow.text()).toContain('合规建设')
    expect(themeRow.text()).toContain('3 条 · 逾期 1 · 风险 1')
    expect(wrapper.find('.row--project').text()).toContain('年度审计')
    expect(wrapper.findAll('.row--task')).toHaveLength(2)
    expect(wrapper.text()).toContain('补齐季度材料')
  })

  it('未归主题行是空心圆并按输入顺序置底', () => {
    const unassigned = theme({
      themeId: null,
      themeName: '未归主题',
      unassigned: true,
      color: '',
      projects: [project({ projectId: null, projectName: '未指定项目', unspecified: true })],
    })
    const wrapper = mountChart({ groups: [theme(), unassigned] })
    const themeRows = wrapper.findAll('.row--theme')
    expect(themeRows).toHaveLength(2)
    expect(themeRows[1].text()).toContain('未归主题')
    expect(themeRows[1].find('.row__dot--empty').exists()).toBe(true)
    expect(wrapper.text()).toContain('未指定项目')
  })

  it('折叠主题后项目与任务行不再渲染，aria-expanded=false', () => {
    const wrapper = mountChart({ collapsed: new Set([themeKey(3)]) })
    expect(wrapper.findAll('.row--project')).toHaveLength(0)
    expect(wrapper.findAll('.row--task')).toHaveLength(0)
    const toggle = wrapper.find('.row__toggle')
    expect(toggle.attributes('aria-expanded')).toBe('false')
  })

  it('折叠项目只隐藏该项目下的任务', () => {
    const wrapper = mountChart({
      groups: [
        theme({
          projects: [
            project(),
            project({ projectId: 8, projectName: '内控自查', tasks: [task({ id: 12, title: '第二项' })] }),
          ],
        }),
      ],
      collapsed: new Set([projectKey(3, 7)]),
    })
    expect(wrapper.findAll('.row--project')).toHaveLength(2)
    expect(wrapper.findAll('.row--task')).toHaveLength(1)
    expect(wrapper.text()).toContain('第二项')
  })

  it('点折叠按钮向页面抛 toggle，点任务条抛 open(id)', async () => {
    const wrapper = mountChart()
    await wrapper.find('.row__toggle').trigger('click')
    expect(wrapper.emitted('toggle')?.[0]).toEqual([themeKey(3)])
    await wrapper.find('.bar').trigger('click')
    expect(wrapper.emitted('open')?.[0]).toEqual([11])
  })
})

describe('GanttChart · 逾期贴左界与风险夹标（AC-11）', () => {
  it('逾期条渲染在 x=0，带红描边与逾期天数', () => {
    const wrapper = mountChart({
      dayW: 96,
      groups: [
        theme({
          projects: [
            project({
              tasks: [
                task({
                  status: 'PENDING',
                  statusLabel: '待开始',
                  startDate: '2026-09-01',
                  dueDate: '2026-09-02',
                  isOverdue: true,
                  overdueDays: 40,
                  pinnedStart: true,
                }),
              ],
            }),
          ],
        }),
      ],
    })
    const bar = wrapper.find('.bar')
    expect(bar.classes()).toContain('bar--overdue')
    expect((bar.element as HTMLElement).style.left).toBe('0px')
    expect(bar.find('.bar__overdue').text()).toBe('逾期 40 天')
  })

  it('右界外的风险条打红色三角夹标，tooltip 提示窗口外', () => {
    const wrapper = mountChart({
      groups: [
        theme({
          projects: [
            project({
              tasks: [
                task({ dueDate: '2027-03-01', isAtRisk: true, pinnedEnd: true, status: 'PENDING_ACCEPT', statusLabel: '待验收' }),
              ],
            }),
          ],
        }),
      ],
    })
    const clamp = wrapper.find('.bar__clamp')
    expect(clamp.exists()).toBe(true)
    expect(clamp.attributes('title')).toContain('（窗口外）')
  })

  it('按创建日推算的任务显示 ≈ 与虚线帽，tooltip 说明来源', () => {
    const wrapper = mountChart({
      groups: [
        theme({
          projects: [project({ tasks: [task({ estimatedStart: true, plannedStartDate: null })] })],
        }),
      ],
    })
    expect(wrapper.find('.row__approx').exists()).toBe(true)
    expect(wrapper.find('.bar__cap--estimated').exists()).toBe(true)
    expect(wrapper.find('.tip').text()).toContain('按创建日推算')
  })

  it('已取消的任务是虚线中轴，不填充语义色', () => {
    const wrapper = mountChart({
      groups: [
        theme({ projects: [project({ tasks: [task({ status: 'CANCELLED', statusLabel: '已取消' })] })] }),
      ],
    })
    expect(wrapper.find('.bar').classes()).toContain('bar--CANCELLED')
  })

  it('tooltip 五行：标题/负责人/来源/起止/状态', () => {
    const wrapper = mountChart()
    const tip = wrapper.find('.tip').text()
    expect(tip).toContain('补齐季度材料')
    expect(tip).toContain('张三')
    expect(tip).toContain('周例会')
    expect(tip).toContain('11-02 → 11-20')
    expect(tip).toContain('进行中')
  })

  it('今天线落在时间轴上', () => {
    const wrapper = mountChart()
    expect(wrapper.find('.gantt__today').exists()).toBe(true)
    expect(wrapper.find('.gantt__today-label').text()).toBe('今天')
  })
})

describe('GanttChart · 空态 / 骨架 / 截断（AC-10、AC-12）', () => {
  it('窗口内 0 条不画网格，给默认空态', () => {
    const wrapper = mountChart({ groups: [] })
    expect(wrapper.find('.gantt__grid').exists()).toBe(false)
    expect(wrapper.text()).toContain('这个时间范围内没有任务')
  })

  it('页面可以通过 #empty 覆盖空态文案与动作', () => {
    const wrapper = mount(GanttChart, {
      props: {
        groups: [],
        window,
        collapsed: new Set<string>(),
        dayW: 12,
        loading: false,
        error: null,
        truncated: false,
        windowTotal: 0,
        today: TODAY,
      },
      slots: { empty: '<p class="custom-empty">当前窗口内无风险任务</p>' },
    })
    expect(wrapper.find('.custom-empty').exists()).toBe(true)
  })

  it('加载中只出骨架，不闪旧数据', () => {
    const wrapper = mountChart({ loading: true })
    expect(wrapper.find('.gantt__skeleton').exists()).toBe(true)
    expect(wrapper.find('.row--theme').exists()).toBe(false)
  })

  it('截断时提示窗口总数与 300 上限', () => {
    const wrapper = mountChart({ truncated: true, windowTotal: 512 })
    expect(wrapper.find('.gantt__notice').text()).toContain('窗口内共 512 条，当前显示 300 条')
  })

  it('错误时整块画布换成 ErrorState，并可重试', async () => {
    const wrapper = mountChart({
      error: new ApiError({ status: 500, code: 'INTERNAL', message: '服务开小差', traceId: 'trace-1' }),
    })
    expect(wrapper.text()).toContain('甘特图加载失败')
    await wrapper.find('.error-state button').trigger('click')
    expect(wrapper.emitted('retry')).toHaveLength(1)
  })
})
