// @vitest-environment jsdom
/**
 * T7 会议任务进度区（AC-4 / AC-5 / AC-3 / AC-9）。
 *
 * 钉住的接缝：
 * - 四个数字全部由 `GET /meetings/{id}/tasks` 这一份数组算出：页头不再读会议行上的
 *   taskCount / unfinishedCount，过期数据不会和进度区打架（AC-4「不得两套计数」）；
 * - 转任务必须先经过弹窗（主题与计划开始一次统一），不允许从列表页直接火并丢掉主题；
 * - `?item=<决议项>` 高亮对应决议行；`Enter` 保存后立刻开下一行并把焦点放回「描述」。
 */
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import { createPinia } from 'pinia'
import { createMemoryHistory, createRouter } from 'vue-router'
import type { DecisionItemRow, MeetingDetail, TaskRow } from '@/types/api'

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
  tasks: vi.fn(),
}))
const metaApiMock = vi.hoisted(() => ({ options: vi.fn() }))

vi.mock('@/api/meetings', async (importOriginal) => {
  const actual = await importOriginal<typeof import('@/api/meetings')>()
  return { ...actual, meetingApi: meetingApiMock }
})
vi.mock('@/api/meta', () => ({ metaApi: metaApiMock }))

import { ApiError } from '@/api/http'
import MeetingDetailPage from '@/pages/MeetingDetailPage.vue'
import MeetingTaskProgress from '@/components/meeting/MeetingTaskProgress.vue'

function task(over: Partial<TaskRow> = {}): TaskRow {
  return {
    id: 71,
    title: '完成接口联调并提交报告',
    owner: { id: 3, name: '李负责', role: 'OWNER' },
    dueDate: '2026-11-20',
    source: { type: 'MEETING', typeLabel: '会议决议', meetingId: 1, meetingTitle: '十一月项目推进会' },
    status: 'IN_PROGRESS',
    isOverdue: false,
    overdueDays: 0,
    dueSoon: false,
    urgeCount: 0,
    lastUrgedAt: null,
    updatedAt: '2026-11-10T09:00:00+08:00',
    completedAt: null,
    theme: { id: 2, name: '交付质量', color: '#2563EB', softColor: '#EFF4FE', borderColor: '#BFD3F6', enabled: true },
    plannedStartDate: '2026-11-13',
    atRisk: false,
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
    id: 1,
    title: '十一月项目推进会',
    meetingDate: '2026-11-10',
    host: '张伟',
    // 故意给一组过期的会议行计数：页面若还读它们，就会和进度区对不上
    taskCount: 99,
    unfinishedCount: 99,
    closed: false,
    archived: false,
    attendees: [{ id: 3, name: '李负责', role: 'OWNER' }],
    note: null,
    items: [
      item(),
      item({ id: 12, description: '补齐验收材料', converted: true, taskId: 70, taskStatus: 'DONE' }),
      item({ id: 14, description: '逾期未处理', converted: true, taskId: 73, taskStatus: 'PENDING_ACCEPT' }),
    ],
    ...over,
  }
}

let wrapper: ReturnType<typeof mount> | null = null

async function mountDetail(path = '/meetings/1') {
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/meetings/:id', component: MeetingDetailPage },
      { path: '/meetings', component: { template: '<div class="meetings-stub">会议列表</div>' } },
      { path: '/tasks/:id', component: { template: '<div class="task-stub">任务详情</div>' } },
    ],
  })
  await router.push(path)
  await router.isReady()
  wrapper = mount(
    { template: '<router-view />' },
    {
      global: { plugins: [router, createPinia()], stubs: { teleport: true } },
      attachTo: document.body,
    },
  )
  await flushPromises()
  return { router }
}

function buttonByText(text: string) {
  const found = wrapper!.findAll('button').filter((btn) => btn.text() === text)
  if (!found.length) throw new Error(`找不到按钮：${text}`)
  return found[0]
}

beforeEach(() => {
  vi.clearAllMocks()
  metaApiMock.options.mockResolvedValue({
    owners: [
      { id: 3, name: '李负责', role: 'OWNER', status: 'ACTIVE' },
      { id: 4, name: '王执行', role: 'EXEC', status: 'ACTIVE' },
    ],
    meetings: [],
    sourceTypes: [],
    statuses: [],
    priorities: [],
    themes: [{ id: 2, name: '交付质量', color: '#2563EB', softColor: '#EFF4FE', borderColor: '#BFD3F6', sort: 1, enabled: true }],
  })
  meetingApiMock.get.mockResolvedValue(meetingDetail())
  meetingApiMock.tasks.mockResolvedValue([
    task(),
    task({ id: 72, title: '补齐验收材料', status: 'DONE', isOverdue: false }),
    task({ id: 73, title: '逾期未处理', isOverdue: true, overdueDays: 6, status: 'PENDING_ACCEPT', atRisk: true }),
  ])
  meetingApiMock.addItem.mockResolvedValue(item({ id: 13 }))
  meetingApiMock.convert.mockResolvedValue({ created: [{ itemId: 11, taskId: 90 }], skipped: [] })
})

afterEach(() => {
  wrapper?.unmount()
  wrapper = null
  document.body.innerHTML = ''
})

describe('MeetingTaskProgress（四计数单一口径）', () => {
  it('四个数字来自同一份数组：已转 3/4、未完成 2、逾期 1、已完成 1', async () => {
    const local = mount(MeetingTaskProgress, {
      props: {
        items: [task(), task({ id: 72, status: 'DONE' }), task({ id: 73, isOverdue: true, status: 'PENDING_ACCEPT' })],
        decisionTotal: 4,
      },
    })
    const stats = local.findAll('.stat').map((node) => node.find('.stat__label').text())
    expect(stats).toEqual(['已转任务', '未完成', '逾期', '已完成'])
    const values = local.findAll('.stat__value').map((node) => node.text())
    expect(values).toEqual(['3/4', '2', '1', '1'])
    expect(local.find('.progress__state').text()).toBe('未完成 2 条')
    local.unmount()
  })

  it('全部完成显示「已闭环」，没有任务显示「待派发」与空态引导', async () => {
    const done = mount(MeetingTaskProgress, {
      props: { items: [task({ status: 'DONE' }), task({ id: 72, status: 'DONE' })], decisionTotal: 2 },
    })
    expect(done.find('.progress__state').text()).toBe('已闭环')
    done.unmount()

    const empty = mount(MeetingTaskProgress, { props: { items: [], decisionTotal: 2 } })
    expect(empty.find('.progress__state').text()).toBe('待派发')
    expect(empty.text()).toContain('暂无已转任务')
    expect(empty.findAll('.table__row')).toHaveLength(0)
    empty.unmount()
  })

  it('加载失败给重试入口，点击行只上报任务 id', async () => {
    const failing = mount(MeetingTaskProgress, {
      props: {
        items: [],
        decisionTotal: 1,
        error: new ApiError({ status: 500, code: 'INTERNAL', message: '服务暂时不可用' }),
      },
    })
    expect(failing.text()).toContain('任务进度加载失败')
    await failing.find('button').trigger('click')
    expect(failing.emitted('retry')).toHaveLength(1)
    failing.unmount()

    const rows = mount(MeetingTaskProgress, { props: { items: [task()], decisionTotal: 1 } })
    await rows.find('.table__row').trigger('click')
    expect(rows.emitted('open')).toEqual([[71]])
    rows.unmount()
  })
})

describe('MeetingDetailPage 任务进度与转任务', () => {
  it('页头不再显示会议行上的旧计数，进度区四个数字只来自 /tasks', async () => {
    await mountDetail()
    const text = wrapper!.text()
    // 会议行给的是 99/99：任何一处显示 99 都说明还有第二套计数
    expect(text).not.toContain('99')
    expect(text).toContain('任务进度')
    const values = wrapper!.findAll('.stat__value').map((node) => node.text())
    expect(values).toEqual(['3/3', '2', '1', '1'])
    expect(wrapper!.find('.progress__state').text()).toBe('未完成 2 条')
  })

  it('转任务先开弹窗，主题与计划开始统一后随请求一次提交', async () => {
    await mountDetail()
    await buttonByText('转为任务').trigger('click')
    await flushPromises()

    // 没有直接发请求：主题/计划开始必须在弹窗里选
    expect(meetingApiMock.convert).not.toHaveBeenCalled()
    expect(wrapper!.text()).toContain('已勾选 1 条决议')

    const dialog = wrapper!.find('.convert__fields')
    const themeSelect = dialog.find('select')
    expect(themeSelect.findAll('option').map((node) => node.text())).toContain('交付质量')
    await themeSelect.setValue('2')
    await dialog.find('input[type="date"]').setValue('2026-11-13')

    await buttonByText('转换为任务（1 条）').trigger('click')
    await flushPromises()

    expect(meetingApiMock.convert).toHaveBeenCalledTimes(1)
    expect(meetingApiMock.convert).toHaveBeenCalledWith(1, {
      itemIds: [11],
      themeId: 2,
      plannedStartDate: '2026-11-13',
    })
    // 转换成功后重取明细与任务清单
    expect(meetingApiMock.tasks).toHaveBeenCalledTimes(2)
  })

  it('?item=12 时高亮对应决议行并滚动到位', async () => {
    await mountDetail('/meetings/1?item=12')
    const row = wrapper!.find('[data-item="12"]')
    expect(row.exists()).toBe(true)
    expect(row.classes()).toContain('is-highlight')
  })

  it('新决议行按 Enter 保存后立刻开下一行，焦点回到「描述」', async () => {
    await mountDetail()
    await buttonByText('+ 添加决议事项').trigger('click')
    await flushPromises()

    await wrapper!.find('[data-cell="new:description"] input').setValue('整理验收清单')
    await wrapper!.find('[data-cell="new:ownerId"] select').setValue('4')
    await wrapper!.find('[data-cell="new:dueDate"] input').setValue('2026-11-28')
    await wrapper!.find('[data-cell="new:description"] input').trigger('keydown.enter')
    await flushPromises()

    expect(meetingApiMock.addItem).toHaveBeenCalledTimes(1)
    expect(meetingApiMock.addItem.mock.calls[0][1]).toMatchObject({
      description: '整理验收清单',
      ownerId: 4,
      dueDate: '2026-11-28',
    })
    // 保存成功后仍在录入态，焦点在新行的描述输入
    const nextDescription = wrapper!.find('[data-cell="new:description"] input')
    expect(nextDescription.exists()).toBe(true)
    expect(document.activeElement).toBe(nextDescription.element)
  })
})
