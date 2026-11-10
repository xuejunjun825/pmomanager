// @vitest-environment jsdom
/**
 * 矩阵 / 看板的项目筛选与项目位（r4 T09 / AC-5、AC-7）。
 *
 * 真实挂载两个页面，只替身网络层。钉住四件事：
 * 1. 两页的下拉都来自 meta 的同一 `projectOptions` 投影（含「未指定项目」与停用后缀），
 *    不是各页自己拼选项（AC-5）；
 * 2. 选中项目后请求确实带 `project=<id>`，且矩阵下钻链接把这个条件带到任务池；
 * 3. `PROJECT_INVALID`（手改 URL / 已删项目）→ 清空该项 + toast + 重取，不停在错误态；
 * 4. 看板焦点列表第二行按 `projectView` 渲染项目标签，会议/说明型来源不渲染。
 */
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import { createPinia } from 'pinia'
import { createMemoryHistory, createRouter } from 'vue-router'
import type { DashboardView, MatrixView, TaskRow } from '@/types/api'

const matrixApiMock = vi.hoisted(() => ({ load: vi.fn() }))
const dashboardApiMock = vi.hoisted(() => ({ load: vi.fn() }))
const metaApiMock = vi.hoisted(() => ({ options: vi.fn() }))
// 看板焦点列表内嵌任务详情抽屉，抽屉挂载即拉进度（不替身会打真实 XHR）。
const taskProgressMock = vi.hoisted(() => vi.fn())

vi.mock('@/api/matrix', async (importOriginal) => {
  const actual = await importOriginal<typeof import('@/api/matrix')>()
  return { ...actual, matrixApi: matrixApiMock }
})
vi.mock('@/api/dashboard', async (importOriginal) => {
  const actual = await importOriginal<typeof import('@/api/dashboard')>()
  return { ...actual, dashboardApi: dashboardApiMock }
})
vi.mock('@/api/meta', async (importOriginal) => {
  const actual = await importOriginal<typeof import('@/api/meta')>()
  return { ...actual, metaApi: metaApiMock }
})
vi.mock('@/api/tasks', async (importOriginal) => {
  const actual = await importOriginal<typeof import('@/api/tasks')>()
  return { ...actual, taskApi: { ...actual.taskApi, progress: taskProgressMock } }
})

const { ApiError } = await import('@/api/http')
const { useUiStore } = await import('@/stores/ui')
const MatrixPage = (await import('@/pages/MatrixPage.vue')).default
const DashboardPage = (await import('@/pages/DashboardPage.vue')).default

const TODAY = '2026-11-09'
const PROJECTS = [
  { id: 9, name: '停用项目', enabled: false, sort: 2 },
  { id: 12, name: '老旧小区改造', enabled: true, sort: 1 },
]

function matrixView(): MatrixView {
  return {
    today: TODAY,
    rangeFrom: '2026-11-01',
    rangeTo: '2026-11-30',
    rows: [
      {
        userId: 3,
        name: '李负责',
        role: 'OWNER',
        userStatus: 'ACTIVE',
        pending: 2,
        inProgress: 1,
        pendingAccept: 0,
        overdue: 3,
        done: 5,
        cancelled: 1,
        total: 11,
        completionRate: 45.5,
      },
    ],
    totals: {
      userId: 0,
      name: '合计',
      role: 'OWNER',
      userStatus: 'ACTIVE',
      pending: 2,
      inProgress: 1,
      pendingAccept: 0,
      overdue: 3,
      done: 5,
      cancelled: 1,
      total: 11,
      completionRate: 45.5,
    },
  }
}

function task(over: Partial<TaskRow> = {}): TaskRow {
  return {
    id: 7,
    title: '季度复盘材料',
    owner: { id: 3, name: '李负责', role: 'OWNER' },
    dueDate: '2026-11-05',
    source: { type: 'PROJECT', typeLabel: '项目任务', form: 'PROJECT', projectId: 12, projectName: '老旧小区改造', projectEnabled: true },
    status: 'IN_PROGRESS',
    isOverdue: true,
    overdueDays: 4,
    dueSoon: false,
    urgeCount: 1,
    lastUrgedAt: '2026-11-08T09:00:00+08:00',
    updatedAt: '2026-11-08T09:00:00+08:00',
    completedAt: null,
    ...over,
  }
}

function dashboardView(): DashboardView {
  return {
    today: TODAY,
    kpis: { total: 2, unfinished: 2, overdue: 1, overdueRate: 50, dueSoon24h: 1, pendingAccept: 0 },
    focus: [
      task(),
      task({ id: 8, title: '临时说明任务', source: { type: 'NOTE', typeLabel: '临时说明', form: 'NOTE', note: '口头安排' } }),
    ],
    focusTotal: 2,
  }
}

async function mountAt(component: unknown, routePath: string, location = routePath) {
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: routePath, component: component as never },
      { path: '/tasks', component: { template: '<div class="pool-stub">任务池</div>' } },
    ],
  })
  await router.push(location)
  await router.isReady()
  const pinia = createPinia()
  const wrapper = mount({ template: '<router-view />' }, { global: { plugins: [router, pinia] } })
  await flushPromises()
  return { wrapper, router, pinia }
}

/** 页面里唯一的可搜索项目下拉（来源/会议仍是原生 select，不能用位置或 class 猜） */
function projectSelect(wrapper: Awaited<ReturnType<typeof mountAt>>['wrapper']) {
  const field = wrapper
    .findAll('.field')
    .find((item) => item.find('input[role="combobox"]').exists())
  if (!field) throw new Error('页面上找不到项目搜索下拉')
  return field
}

/** 真实交互：点开 combobox，再点列表项（不直接 emit 事件，才能覆盖组件的可访问性路径） */
async function pickProject(wrapper: Awaited<ReturnType<typeof mountAt>>['wrapper'], label: string) {
  const field = projectSelect(wrapper)
  await field.find('.field__control').trigger('mousedown')
  await flushPromises()
  const option = field.findAll('.list .option').find((item) => item.text().includes(label))
  if (!option) throw new Error(`项目下拉里找不到：${label}`)
  await option.trigger('click')
  await flushPromises()
}

beforeEach(() => {
  vi.clearAllMocks()
  metaApiMock.options.mockResolvedValue({
    owners: [],
    meetings: [],
    sourceTypes: [],
    statuses: [],
    priorities: [],
    projects: PROJECTS,
  })
  matrixApiMock.load.mockResolvedValue(matrixView())
  dashboardApiMock.load.mockResolvedValue(dashboardView())
  taskProgressMock.mockResolvedValue({ items: [], total: 0, hasMore: false })
})

describe('MatrixPage 项目筛选', () => {
  it('下拉来自统一投影：全部 / 未指定 / 项目（停用带后缀）', async () => {
    const { wrapper } = await mountAt(MatrixPage, '/matrix')
    const field = projectSelect(wrapper)
    await field.find('.field__control').trigger('mousedown')
    await flushPromises()

    const labels = field.findAll('.list .option .option__text').map((item) => item.text())
    // 投影顺序：占位「全部项目」→ 启用项（sort）→ 停用项（带后缀）→「未指定项目」独立一档
    expect(labels).toEqual([
      '全部项目',
      '老旧小区改造',
      '停用项目（已停用）',
      '未指定项目',
    ])
  })

  it('选中项目后请求带 project，下钻链接把同一条件带到任务池', async () => {
    const { wrapper } = await mountAt(MatrixPage, '/matrix')
    await pickProject(wrapper, '老旧小区改造')

    expect(matrixApiMock.load).toHaveBeenLastCalledWith(expect.objectContaining({ project: '12' }))
    const href = wrapper.find('a.mc--link').attributes('href') ?? ''
    expect(href).toContain('project=12')
    expect(href.startsWith('/tasks?')).toBe(true)
  })

  it('URL 里的未知项目（已删 / 手改链接）：清空筛选项 + toast 并按全部项目重取', async () => {
    matrixApiMock.load.mockRejectedValueOnce(
      new ApiError({ status: 400, code: 'PROJECT_INVALID', message: '项目已删除' }),
    )
    const { wrapper, pinia } = await mountAt(MatrixPage, '/matrix', '/matrix?project=999')
    await flushPromises()
    await flushPromises()

    expect(useUiStore(pinia).toasts.some((toast) => toast.message.includes('项目不合法'))).toBe(true)
    // 落回「全部项目」，且重取时不再带 project
    expect(projectSelect(wrapper).find('input').element.value).toBe('全部项目')
    expect(matrixApiMock.load).toHaveBeenLastCalledWith(expect.objectContaining({ project: undefined }))
  })
})

describe('DashboardPage 项目筛选与项目位', () => {
  it('选中项目后看板请求带 project（与任务池同名同义）', async () => {
    const { wrapper } = await mountAt(DashboardPage, '/dashboard')
    await pickProject(wrapper, '未指定项目')

    expect(dashboardApiMock.load).toHaveBeenLastCalledWith(expect.objectContaining({ project: 'none' }))
  })

  it('焦点列表第二行按 projectView 渲染项目标签，说明型来源不渲染', async () => {
    const { wrapper } = await mountAt(DashboardPage, '/dashboard')
    const rows = wrapper.findAll('tbody tr')
    expect(rows).toHaveLength(2)

    const tags = rows[0].findAll('.title-project .tag')
    expect(tags).toHaveLength(1)
    expect(tags[0].text()).toContain('老旧小区改造')
    // 说明型来源（form=NOTE）不产生项目位，也不该出现「未指定项目」
    expect(rows[1].findAll('.title-project .tag')).toHaveLength(0)
    expect(rows[1].find('.tag--unspecified').exists()).toBe(false)
  })

  it('URL 里的未知项目：清空并 toast，界面不停在错误态', async () => {
    dashboardApiMock.load.mockRejectedValueOnce(
      new ApiError({ status: 400, code: 'PROJECT_INVALID', message: '项目已删除' }),
    )
    const { wrapper, pinia } = await mountAt(DashboardPage, '/dashboard', '/dashboard?project=999')
    await flushPromises()
    await flushPromises()

    expect(useUiStore(pinia).toasts.some((toast) => toast.message.includes('项目不合法'))).toBe(true)
    expect(wrapper.text()).not.toContain('出错了')
    expect(dashboardApiMock.load).toHaveBeenLastCalledWith(expect.objectContaining({ project: undefined }))
  })
})
