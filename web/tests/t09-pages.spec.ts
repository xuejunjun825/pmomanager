// @vitest-environment jsdom
/**
 * T09 页面级验证（会议列表 / 会议详情 / 矩阵 / 看板）。
 *
 * 只替身 api 模块，页面、路由、组件保持真实。钉住的接缝是：
 * - 矩阵每格下钻参数与该格口径一致，0 与合计行不可点（AC-15 / AC-17）；
 * - 看板 6 卡 + 逾期红条 + 管理层没有验收入口（AC-16）；
 * - 会议决议逐项校验错误落到具体单元格，转任务同时报 created / skipped（AC-3 / AC-4）。
 */
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import { createPinia } from 'pinia'
import { createMemoryHistory, createRouter } from 'vue-router'
import type { Component } from 'vue'
import type { DashboardView, DecisionItemRow, MatrixView, MeetingDetail, MeetingRow, TaskRow } from '@/types/api'

const meetingApiMock = vi.hoisted(() => ({
  list: vi.fn(),
  get: vi.fn(),
  create: vi.fn(),
  update: vi.fn(),
  archive: vi.fn(),
  addItem: vi.fn(),
  updateItem: vi.fn(),
  deleteItem: vi.fn(),
  convert: vi.fn(),
}))
const matrixApiMock = vi.hoisted(() => ({ load: vi.fn() }))
const dashboardApiMock = vi.hoisted(() => ({ load: vi.fn() }))
const metaApiMock = vi.hoisted(() => ({ options: vi.fn() }))
const taskApiMock = vi.hoisted(() => ({
  reassign: vi.fn(),
  reschedule: vi.fn(),
  urge: vi.fn(),
}))

vi.mock('@/api/meetings', async (importOriginal) => {
  const actual = await importOriginal<typeof import('@/api/meetings')>()
  return { ...actual, meetingApi: meetingApiMock }
})
vi.mock('@/api/matrix', async (importOriginal) => {
  const actual = await importOriginal<typeof import('@/api/matrix')>()
  return { ...actual, matrixApi: matrixApiMock }
})
vi.mock('@/api/dashboard', () => ({ dashboardApi: dashboardApiMock }))
vi.mock('@/api/meta', () => ({ metaApi: metaApiMock }))
vi.mock('@/api/tasks', async (importOriginal) => {
  const actual = await importOriginal<typeof import('@/api/tasks')>()
  return { ...actual, taskApi: { ...actual.taskApi, ...taskApiMock } }
})

import { ApiError } from '@/api/http'
import { useUiStore } from '@/stores/ui'
import MeetingsPage from '@/pages/MeetingsPage.vue'
import MeetingDetailPage from '@/pages/MeetingDetailPage.vue'
import MatrixPage from '@/pages/MatrixPage.vue'
import DashboardPage from '@/pages/DashboardPage.vue'

const TODAY = '2026-11-09'

function meeting(over: Partial<MeetingRow> = {}): MeetingRow {
  return {
    id: 1,
    title: '十月项目推进会',
    meetingDate: '2026-11-01',
    host: '张伟',
    archived: false,
    taskCount: 3,
    unfinishedCount: 2,
    closed: false,
    ...over,
  }
}

function item(over: Partial<DecisionItemRow> = {}): DecisionItemRow {
  return {
    id: 11,
    description: '完成接口联调并提交报告',
    owner: { id: 3, name: '李负责', role: 'OWNER' },
    dueDate: '2026-11-20',
    deliveryNote: null,
    sortNo: 1,
    converted: false,
    taskId: null,
    taskStatus: null,
    isOverdue: false,
    overdueDays: 0,
    ...over,
  }
}

function meetingDetail(over: Partial<MeetingDetail> = {}): MeetingDetail {
  return {
    ...meeting(),
    attendees: [{ id: 3, name: '李负责', role: 'OWNER' }],
    note: '决议已确认',
    items: [
      item(),
      item({ id: 12, description: '补齐验收材料', converted: true, taskId: 77, taskStatus: 'IN_PROGRESS' }),
    ],
    ...over,
  }
}

function task(over: Partial<TaskRow> = {}): TaskRow {
  return {
    id: 7,
    title: '季度复盘材料',
    owner: { id: 3, name: '李负责', role: 'OWNER' },
    dueDate: '2026-11-05',
    source: { type: 'MEETING', typeLabel: '会议决议', meetingId: 1, meetingTitle: '十月项目推进会' },
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

function matrixView(over: Partial<MatrixView> = {}): MatrixView {
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
      {
        userId: 4,
        name: '王执行',
        role: 'EXEC',
        userStatus: 'ACTIVE',
        pending: 0,
        inProgress: 2,
        pendingAccept: 1,
        overdue: 0,
        done: 1,
        cancelled: 0,
        total: 4,
        completionRate: 25,
      },
    ],
    totals: {
      userId: 0,
      name: '合计',
      role: null,
      userStatus: null,
      pending: 2,
      inProgress: 3,
      pendingAccept: 1,
      overdue: 3,
      done: 6,
      cancelled: 1,
      total: 15,
      completionRate: 40,
    },
    ...over,
  } as MatrixView
}

function dashboardView(over: Partial<DashboardView> = {}): DashboardView {
  return {
    today: TODAY,
    kpis: { total: 20, unfinished: 12, overdue: 5, overdueRate: 41.7, dueSoon24h: 2, pendingAccept: 3 },
    focus: [task(), task({ id: 8, title: '预算复核', isOverdue: false, overdueDays: 0, dueSoon: true, urgeCount: 0, lastUrgedAt: null })],
    focusTotal: 12,
    ...over,
  }
}

async function mountAt(component: Component, path: string, pattern = path) {
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: pattern, component },
      { path: '/tasks', component: { template: '<div class="pool-stub">任务池</div>' } },
      { path: '/tasks/:id', component: { template: '<div class="task-stub">任务详情</div>' } },
      { path: '/meetings', component: { template: '<div class="meetings-stub">会议列表</div>' } },
      { path: '/meetings/:id', component: { template: '<div class="meeting-stub">会议详情</div>' } },
    ],
  })
  await router.push(path)
  await router.isReady()
  const pinia = createPinia()
  const wrapper = mount(
    { template: '<router-view />' },
    { global: { plugins: [router, pinia], stubs: { teleport: true } } },
  )
  await flushPromises()
  return { wrapper, router, pinia }
}

type PageWrapper = Awaited<ReturnType<typeof mountAt>>['wrapper']

function buttonByText(wrapper: PageWrapper, text: string) {
  return wrapper.findAll('button').filter((btn) => btn.text() === text)
}

/** 决议表格行内的按钮（页头也有一个「编辑」，作用域必须限定在 tbody）。 */
function rowButton(wrapper: PageWrapper, text: string) {
  const found = wrapper.findAll('tbody button').filter((btn) => btn.text() === text)
  if (!found.length) throw new Error(`决议表格行内找不到按钮：${text}`)
  return found[0]
}

beforeEach(() => {
  vi.clearAllMocks()
  metaApiMock.options.mockResolvedValue({
    owners: [
      { id: 3, name: '李负责', role: 'OWNER', status: 'ACTIVE' },
      { id: 4, name: '王执行', role: 'EXEC', status: 'ACTIVE' },
    ],
    meetings: [{ id: 1, title: '十月项目推进会', meetingDate: '2026-11-01' }],
    sourceTypes: [],
    statuses: [],
    priorities: [],
  })
  meetingApiMock.list.mockResolvedValue({ items: [meeting()], total: 1, page: 1, size: 20 })
  meetingApiMock.get.mockResolvedValue(meetingDetail())
  meetingApiMock.archive.mockResolvedValue(undefined)
  meetingApiMock.updateItem.mockResolvedValue(item())
  matrixApiMock.load.mockResolvedValue(matrixView())
  dashboardApiMock.load.mockResolvedValue(dashboardView())
})

describe('MatrixPage', () => {
  it('每格下钻参数与该格口径一致：非逾期列带 overdue=false，逾期列只按 overdue', async () => {
    const { wrapper } = await mountAt(MatrixPage, '/matrix')
    const rows = wrapper.findAll('tbody tr')
    const links = rows[0].findAll('a.mc--link').map((link) => link.attributes('href') ?? '')

    // 第一行：待开始 2 / 进行中 1 / 待验收 0（不可点）/ 逾期 3 / 已完成 5 / 已取消 1
    expect(links).toHaveLength(5)
    expect(links[0]).toContain('status=PENDING')
    expect(links[0]).toContain('overdue=false')
    expect(links[1]).toContain('status=IN_PROGRESS')
    expect(links[1]).toContain('overdue=false')
    expect(links[2]).toContain('overdue=true')
    expect(links[2]).not.toContain('status=')
    expect(links[3]).toContain('status=DONE')
    expect(links[4]).toContain('status=CANCELLED')
    for (const href of links) expect(href.startsWith('/tasks?')).toBe(true)
    expect(links[0]).toContain('owner=3')
    // 第二行待开始为 0：同一个列位置是纯文本，不下钻
    expect(rows[1].findAll('td')[0].find('a').exists()).toBe(false)
  })

  it('0 不可点、合计行不下钻，口径脚注常驻', async () => {
    const { wrapper } = await mountAt(MatrixPage, '/matrix')
    const zeros = wrapper.findAll('.mc--zero')
    expect(zeros.length).toBeGreaterThan(0)
    expect(zeros[0].element.tagName).toBe('SPAN')

    const totalsRow = wrapper.find('tfoot tr')
    expect(totalsRow.exists()).toBe(true)
    expect(totalsRow.text()).toContain('合计')
    expect(totalsRow.findAll('a')).toHaveLength(0)
    expect(totalsRow.text()).toContain('15')

    const page = wrapper.text()
    expect(page).toContain('逾期任务只计入「逾期」列，不重复计入原状态列')
    expect(page).toContain('五个数字列互斥')
    expect(wrapper.find('.cell--overdue').exists()).toBe(true)
  })
})

describe('DashboardPage', () => {
  it('6 个 KPI，逾期卡带红条，构成条与焦点列表来自同一响应', async () => {
    const { wrapper } = await mountAt(DashboardPage, '/dashboard')
    expect(wrapper.findAll('.kpi')).toHaveLength(6)

    const overdueCard = wrapper.findAll('.kpi').find((card) => card.text().includes('逾期率') === false && card.text().includes('逾期'))
    expect(overdueCard?.classes()).toContain('kpi--danger')

    expect(wrapper.findAll('.bar__seg')).toHaveLength(3)
    expect(wrapper.text()).toContain('占未完成')
    expect(wrapper.findAll('tbody tr')).toHaveLength(2)
    expect(dashboardApiMock.load).toHaveBeenCalledTimes(1)
  })

  it('管理层没有验收入口，改派必须填原因后才调接口', async () => {
    const { wrapper, pinia } = await mountAt(DashboardPage, '/dashboard')
    const text = wrapper.text()
    for (const forbidden of ['验收通过', '打回', '代确认', '新建任务']) {
      expect(text).not.toContain(forbidden)
    }

    await buttonByText(wrapper, '改派')[0].trigger('click')
    await flushPromises()
    expect(wrapper.find('.form').exists()).toBe(true)

    await wrapper.find('.form').trigger('submit')
    await flushPromises()
    expect(taskApiMock.reassign).not.toHaveBeenCalled()
    expect(wrapper.text()).toContain('请选择新的负责人')

    await wrapper.find('.form select').setValue('4')
    await wrapper.find('.form').trigger('submit')
    await flushPromises()
    expect(taskApiMock.reassign).not.toHaveBeenCalled()
    expect(wrapper.text()).toContain('请填写原因')

    await wrapper.find('.form input').setValue('负责人休假，转交同事继续跟进')
    await wrapper.find('.form').trigger('submit')
    await flushPromises()

    expect(taskApiMock.reassign).toHaveBeenCalledWith(7, 4, '负责人休假，转交同事继续跟进')
    const toasts = useUiStore(pinia).toasts
    expect(toasts.some((toast) => toast.message.includes('已改派'))).toBe(true)
  })
})

describe('MeetingDetailPage', () => {
  it('未转事项默认可勾选、已转事项锁定，转任务同时报 created 与 skipped', async () => {
    meetingApiMock.convert.mockResolvedValue({
      created: [{ itemId: 11, taskId: 90 }],
      skipped: [{ itemId: 12, reason: 'ALREADY_CONVERTED', taskId: 77 }],
    })
    const { wrapper, pinia } = await mountAt(MeetingDetailPage, '/meetings/1', '/meetings/:id')

    const boxes = wrapper.findAll('tbody input[type="checkbox"]')
    expect(boxes).toHaveLength(2)
    expect(boxes[0].attributes('disabled')).toBeUndefined()
    expect(boxes[1].attributes('disabled')).toBeDefined()

    expect(wrapper.find('.convert-bar').text()).toContain('已选 1 项')
    await buttonByText(wrapper, '转为任务')[0].trigger('click')
    await flushPromises()

    // AC-9：转任务要统一挂主题与计划开始，所以「转为任务」只负责开弹窗，
    // 真正发请求的是弹窗里的确认按钮（一次批量作用于全部勾选项）。
    expect(wrapper.find('.modal').exists()).toBe(true)
    expect(wrapper.text()).toContain('已勾选 1 条决议')
    await buttonByText(wrapper, '转换为任务（1 条）')[0].trigger('click')
    await flushPromises()

    expect(meetingApiMock.convert).toHaveBeenCalledWith(1, {
      itemIds: [11],
      themeId: null,
      plannedStartDate: null,
    })
    const toasts = useUiStore(pinia).toasts
    expect(toasts.some((toast) => toast.message === '已生成 1 条任务，1 项此前已转任务已跳过')).toBe(true)
    // 终态：勾选清空、底部转化条消失、详情重新拉取（已转事项在刷新后锁定为不可勾选）
    expect(wrapper.find('.convert-bar').exists()).toBe(false)
    expect(meetingApiMock.get).toHaveBeenCalledTimes(2)
  })

  it('逐项校验：错误回填到该行单元格，且不调用保存接口', async () => {
    const { wrapper } = await mountAt(MeetingDetailPage, '/meetings/1', '/meetings/:id')
    await rowButton(wrapper, '编辑').trigger('click')
    await flushPromises()

    const editingRow = wrapper.find('tbody tr')
    await editingRow.find('.cell-edit input').setValue('')
    await rowButton(wrapper, '保存').trigger('click')
    await flushPromises()

    expect(editingRow.text()).toContain('请填写事项描述')
    expect(meetingApiMock.updateItem).not.toHaveBeenCalled()
  })

  it('服务端 422 的 fieldErrors 落到对应单元格', async () => {
    meetingApiMock.updateItem.mockRejectedValue(
      new ApiError({
        status: 422,
        code: 'VALIDATION_FAILED',
        message: '决议事项校验失败',
        fieldErrors: [{ field: 'ownerId', message: '负责人已停用', index: 0 }],
      }),
    )
    const { wrapper } = await mountAt(MeetingDetailPage, '/meetings/1', '/meetings/:id')
    await rowButton(wrapper, '编辑').trigger('click')
    await flushPromises()
    await rowButton(wrapper, '保存').trigger('click')
    await flushPromises()

    expect(meetingApiMock.updateItem).toHaveBeenCalledTimes(1)
    expect(wrapper.find('tbody tr').text()).toContain('负责人已停用')
  })

  it('已归档会议只读：不可勾选、无转化条、无行内编辑入口', async () => {
    meetingApiMock.get.mockResolvedValue(meetingDetail({ archived: true }))
    const { wrapper } = await mountAt(MeetingDetailPage, '/meetings/1', '/meetings/:id')

    expect(wrapper.text()).toContain('会议已归档')
    expect(wrapper.find('.convert-bar').exists()).toBe(false)
    const firstRow = wrapper.find('tbody tr')
    expect((firstRow.find('input[type="checkbox"]').element as HTMLInputElement).disabled).toBe(true)
    expect(firstRow.findAll('button').some((btn) => btn.text() === '编辑')).toBe(false)
  })
})

describe('MeetingsPage', () => {
  it('列表展示闭环状态；归档走二次确认后才调接口', async () => {
    const { wrapper, pinia } = await mountAt(MeetingsPage, '/meetings')
    expect(wrapper.text()).toContain('十月项目推进会')
    expect(wrapper.text()).toContain('未完成 2 条')

    await buttonByText(wrapper, '归档')[0].trigger('click')
    await flushPromises()
    expect(wrapper.text()).toContain('归档后会议从列表隐藏')

    await buttonByText(wrapper, '归档会议')[0].trigger('click')
    await flushPromises()
    expect(meetingApiMock.archive).toHaveBeenCalledWith(1)
    expect(useUiStore(pinia).toasts.some((toast) => toast.message.includes('会议已归档'))).toBe(true)
  })

  it('「显示已归档」切换的是归档视图（archived=true 精确过滤）', async () => {
    const { wrapper } = await mountAt(MeetingsPage, '/meetings')
    expect(meetingApiMock.list).toHaveBeenLastCalledWith(expect.objectContaining({ archived: false }))

    await wrapper.find('.switch__input').setValue(true)
    await flushPromises()

    expect(meetingApiMock.list).toHaveBeenLastCalledWith(expect.objectContaining({ archived: true }))
  })
})
