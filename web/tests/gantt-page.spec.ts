// @vitest-environment jsdom
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import { createPinia } from 'pinia'
import { createMemoryHistory, createRouter, type Router } from 'vue-router'
import GanttPage from '@/pages/GanttPage.vue'
import { useGanttView } from '@/composables/useGanttView'
import { canSeeNav, canSeePage, navGroupsFor } from '@/utils/permissions'
import type { GanttProjectGroup, GanttTask, GanttThemeGroup, GanttView, MetaOptions } from '@/types/api'

const { loadMock, optionsMock, toastSuccess, toastWarning } = vi.hoisted(() => ({
  loadMock: vi.fn(),
  optionsMock: vi.fn(),
  toastSuccess: vi.fn(),
  toastWarning: vi.fn(),
}))

vi.mock('@/api/gantt', () => ({ ganttApi: { load: loadMock } }))
vi.mock('@/api/meta', () => ({ metaApi: { options: optionsMock } }))
vi.mock('@/composables/useToast', () => ({
  useToast: () => ({ success: toastSuccess, warning: toastWarning, error: vi.fn(), info: vi.fn() }),
}))

const Empty = { template: '<div class="stub-page" />' }
let wrapper: VueWrapper | null = null

function task(overrides: Partial<GanttTask> = {}): GanttTask {
  return {
    id: 11,
    title: '逾期任务',
    owner: { id: 1, name: '张三' },
    status: 'PENDING',
    statusLabel: '待开始',
    startDate: '2026-09-01',
    plannedStartDate: null,
    dueDate: '2026-09-02',
    estimatedStart: true,
    isOverdue: true,
    overdueDays: 71,
    isAtRisk: true,
    pinnedStart: true,
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
  return { projectId: 7, projectName: '年度审计', unspecified: false, taskCount: tasks.length, tasks, ...overrides }
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

function sampleGroups(): GanttThemeGroup[] {
  return [
    theme({
      projects: [
        project({
          tasks: [
            task(),
            task({
              id: 12,
              title: '风险任务',
              status: 'PENDING_ACCEPT',
              statusLabel: '待验收',
              startDate: '2026-11-01',
              dueDate: '2026-11-30',
              estimatedStart: false,
              isOverdue: false,
              overdueDays: 0,
              isAtRisk: true,
              pinnedStart: false,
            }),
            task({
              id: 13,
              title: '已完成任务',
              status: 'DONE',
              statusLabel: '已完成',
              startDate: '2026-11-01',
              dueDate: '2026-11-10',
              estimatedStart: false,
              isOverdue: false,
              overdueDays: 0,
              isAtRisk: false,
              pinnedStart: false,
            }),
          ],
        }),
      ],
    }),
  ]
}

function view(overrides: Partial<GanttView> = {}): GanttView {
  return {
    today: '2026-11-12',
    from: '2026-10-26',
    to: '2027-01-17',
    truncated: false,
    windowTotal: 3,
    summary: { overdue: 1, risk: 2, inProgress: 0, pendingAccept: 1, done: 1, unassigned: 0 },
    groups: sampleGroups(),
    ...overrides,
  }
}

function meta(): MetaOptions {
  return {
    owners: [{ id: 1, name: '张三', role: 'OWNER', status: 'ACTIVE' }],
    meetings: [],
    sourceTypes: [],
    projects: [],
    themes: [],
    statuses: [],
    priorities: [],
  }
}

async function makeRouter(): Promise<Router> {
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/gantt', name: 'gantt', component: Empty },
      { path: '/tasks', name: 'tasks', component: Empty },
      { path: '/tasks/:id(\\d+)', name: 'task-detail', component: Empty },
    ],
  })
  await router.push('/gantt')
  await router.isReady()
  return router
}

async function mountPage(options: { resolve?: GanttView } = {}): Promise<{ wrapper: VueWrapper; router: Router }> {
  const router = await makeRouter()
  if (options.resolve) loadMock.mockResolvedValue(options.resolve)
  wrapper = mount(GanttPage, { global: { plugins: [router, createPinia()] } })
  await flushPromises()
  return { wrapper, router }
}

function lastQuery(): Record<string, unknown> {
  return loadMock.mock.calls[loadMock.mock.calls.length - 1][0] as Record<string, unknown>
}

function chip(wrapper: VueWrapper, label: string) {
  const found = wrapper.findAll('.chip').find((item) => item.text().includes(label))
  if (!found) throw new Error(`chip ${label} not found`)
  return found
}

beforeEach(() => {
  vi.useFakeTimers({ toFake: ['Date'] })
  vi.setSystemTime(new Date(2026, 10, 12, 10, 0, 0))
  useGanttView().reset()
  loadMock.mockReset()
  optionsMock.mockReset()
  loadMock.mockResolvedValue(view())
  optionsMock.mockResolvedValue(meta())
  toastSuccess.mockReset()
  toastWarning.mockReset()
})

afterEach(() => {
  wrapper?.unmount()
  wrapper = null
  vi.useRealTimers()
})

describe('GanttPage · 请求次数与窗口（AC-10、AC-12）', () => {
  it('首屏按周档发 1 次请求：12 周、含已完成、未开风险过滤', async () => {
    await mountPage()
    expect(loadMock).toHaveBeenCalledTimes(1)
    expect(lastQuery()).toMatchObject({
      from: '2026-10-26',
      to: '2027-01-17',
      includeDone: true,
      onlyRisk: false,
    })
    expect(lastQuery().ownerId).toBeUndefined()
  })

  it('切档只发 1 次请求，窗口换成 14 天', async () => {
    const { wrapper } = await mountPage()
    loadMock.mockClear()
    await wrapper.findAll('.seg__btn')[0].trigger('click')
    await flushPromises()
    expect(loadMock).toHaveBeenCalledTimes(1)
    expect(lastQuery()).toMatchObject({ from: '2026-11-11', to: '2026-11-24' })
  })

  it('翻屏只发 1 次请求，窗口整体平移 84 天', async () => {
    const { wrapper } = await mountPage()
    loadMock.mockClear()
    await wrapper.find('button[aria-label="下一屏"]').trigger('click')
    await flushPromises()
    expect(loadMock).toHaveBeenCalledTimes(1)
    expect(lastQuery()).toMatchObject({ from: '2027-01-18', to: '2027-04-11' })
    loadMock.mockClear()
    await wrapper.find('button[aria-label="上一屏"]').trigger('click')
    await flushPromises()
    expect(loadMock).toHaveBeenCalledTimes(1)
    expect(lastQuery()).toMatchObject({ from: '2026-10-26', to: '2027-01-17' })
  })

  it('「回到今天」恢复当前档位默认窗口', async () => {
    const { wrapper } = await mountPage()
    await wrapper.find('button[aria-label="下一屏"]').trigger('click')
    await flushPromises()
    loadMock.mockClear()
    const todayButton = wrapper.findAll('button').find((item) => item.text() === '回到今天')
    await todayButton!.trigger('click')
    await flushPromises()
    expect(loadMock).toHaveBeenCalledTimes(1)
    expect(lastQuery()).toMatchObject({ from: '2026-10-26', to: '2027-01-17' })
  })

  it('过期响应被丢弃：后发先至的窗口赢', async () => {
    const pending: Array<(value: GanttView) => void> = []
    loadMock.mockReset()
    loadMock.mockImplementation(() => new Promise<GanttView>((resolve) => pending.push(resolve)))
    const { wrapper } = await mountPage()
    await wrapper.find('button[aria-label="下一屏"]').trigger('click')
    expect(loadMock).toHaveBeenCalledTimes(2)
    pending[1](view({ windowTotal: 222, groups: [theme({ themeName: '新窗口主题' })] }))
    await flushPromises()
    pending[0](view({ windowTotal: 111, groups: [theme({ themeName: '旧窗口主题' })] }))
    await flushPromises()
    expect(wrapper.text()).toContain('新窗口主题')
    expect(wrapper.text()).not.toContain('旧窗口主题')
    expect(wrapper.text()).toContain('窗口内 222 条')
  })
})

describe('GanttPage · 摘要点击即筛（AC-11）', () => {
  it('点「风险」只筛已返回的数据，不额外请求', async () => {
    const { wrapper } = await mountPage()
    const before = loadMock.mock.calls.length
    await chip(wrapper, '风险').trigger('click')
    await flushPromises()
    expect(loadMock.mock.calls.length).toBe(before)
    expect(wrapper.text()).toContain('风险任务')
    expect(wrapper.text()).not.toContain('已完成任务')
    expect(chip(wrapper, '风险').attributes('aria-pressed')).toBe('true')
  })

  it('再点一次同一数字取消筛选', async () => {
    const { wrapper } = await mountPage()
    await chip(wrapper, '风险').trigger('click')
    await flushPromises()
    await chip(wrapper, '风险').trigger('click')
    await flushPromises()
    expect(wrapper.text()).toContain('已完成任务')
  })

  it('「仅看逾期与风险」清空 chip 并只发 1 次请求', async () => {
    const { wrapper } = await mountPage()
    await chip(wrapper, '风险').trigger('click')
    loadMock.mockClear()
    await wrapper.find('.switch').trigger('click')
    await flushPromises()
    expect(loadMock).toHaveBeenCalledTimes(1)
    expect(lastQuery().onlyRisk).toBe(true)
    expect(wrapper.find('.switch').attributes('aria-pressed')).toBe('true')
    expect(chip(wrapper, '风险').attributes('aria-pressed')).toBe('false')
  })

  it('风险开关打开时点非风险数字，会关掉开关且只发 1 次请求', async () => {
    const { wrapper } = await mountPage()
    await wrapper.find('.switch').trigger('click')
    await flushPromises()
    loadMock.mockClear()
    await chip(wrapper, '待验收').trigger('click')
    await flushPromises()
    expect(loadMock).toHaveBeenCalledTimes(1)
    expect(lastQuery().onlyRisk).toBe(false)
    expect(wrapper.find('.switch').attributes('aria-pressed')).toBe('false')
  })

  it('关闭「显示已完成」后已完成条与计数一起消失，只发 1 次请求', async () => {
    const { wrapper } = await mountPage()
    loadMock.mockClear()
    const toggle = wrapper.findAll('button').find((item) => item.text() === '显示已完成')
    await toggle!.trigger('click')
    await flushPromises()
    expect(loadMock).toHaveBeenCalledTimes(1)
    expect(lastQuery().includeDone).toBe(false)
    expect(wrapper.findAll('.chip').some((item) => item.text().includes('已完成'))).toBe(false)
    expect(wrapper.text()).not.toContain('已完成任务')
  })

  it('无风险时给专属空态文案', async () => {
    const { wrapper } = await mountPage()
    loadMock.mockResolvedValue(view({ groups: [] }))
    await wrapper.find('.switch').trigger('click')
    await flushPromises()
    expect(wrapper.text()).toContain('当前窗口内无风险任务')
  })
})

describe('GanttPage · 折叠、截断与跳转', () => {
  it('折叠主题后翻屏，折叠状态保持', async () => {
    const { wrapper } = await mountPage()
    await wrapper.find('.row__toggle').trigger('click')
    expect(wrapper.findAll('.row--task')).toHaveLength(0)
    await wrapper.find('button[aria-label="下一屏"]').trigger('click')
    await flushPromises()
    expect(wrapper.find('.row__toggle').attributes('aria-expanded')).toBe('false')
    expect(wrapper.findAll('.row--task')).toHaveLength(0)
  })

  it('窗口超过 300 条时显示截断提示', async () => {
    const { wrapper } = await mountPage({
      resolve: view({ truncated: true, windowTotal: 512 }),
    })
    expect(wrapper.find('.gantt__notice').text()).toContain('窗口内共 512 条，当前显示 300 条')
  })

  it('点任务条进入任务详情', async () => {
    const { wrapper, router } = await mountPage()
    await wrapper.find('.bar').trigger('click')
    await flushPromises()
    expect(router.currentRoute.value.path).toBe('/tasks/11')
  })

  it('空窗口给「扩大范围（本季）」与「回到今天」', async () => {
    const { wrapper } = await mountPage({ resolve: view({ groups: [], windowTotal: 0 }) })
    expect(wrapper.text()).toContain('这个时间范围内没有任务')
    const expand = wrapper.findAll('button').find((item) => item.text() === '扩大范围（本季）')
    expect(expand).toBeTruthy()
    loadMock.mockClear()
    await expand!.trigger('click')
    await flushPromises()
    expect(loadMock).toHaveBeenCalledTimes(1)
    expect(lastQuery()).toMatchObject({ from: '2026-10-01', to: '2027-03-31' })
  })
})

describe('GanttPage · 权限与导航（AC-10）', () => {
  it('只有 ADMIN 与 EXEC 能进 /gantt，负责人侧栏不出现甘特', () => {
    expect(canSeePage('ADMIN', '/gantt')).toBe(true)
    expect(canSeePage('EXEC', '/gantt')).toBe(true)
    expect(canSeePage('OWNER', '/gantt')).toBe(false)
    expect(canSeeNav('ADMIN', 'gantt')).toBe(true)
    expect(canSeeNav('OWNER', 'gantt')).toBe(false)
    const ownerNav = navGroupsFor('OWNER').flatMap((group) => group.items.map((item) => item.key))
    expect(ownerNav).not.toContain('gantt')
  })

  it('主题超过 12 个时首屏只展开有风险/逾期的主题（AC-11）', async () => {
    const groups = Array.from({ length: 13 }, (_, index) =>
      theme({
        themeId: index + 1,
        themeName: `主题${index + 1}`,
        overdueCount: index === 0 ? 1 : 0,
        riskCount: 0,
        projects: [project({ projectId: 100 + index, projectName: `项目${index + 1}` })],
      }),
    )
    const { wrapper } = await mountPage({ resolve: view({ groups, windowTotal: 13 }) })
    expect(wrapper.findAll('.row--theme')).toHaveLength(13)
    expect(wrapper.findAll('.row--task')).toHaveLength(1)
    const collapsedCount = wrapper
      .findAll('.row__toggle')
      .filter((item) => item.attributes('aria-expanded') === 'false').length
    expect(collapsedCount).toBe(12)
  })
})
