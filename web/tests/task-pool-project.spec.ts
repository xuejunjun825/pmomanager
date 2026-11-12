// @vitest-environment jsdom
/**
 * T08 任务池项目筛选（AC-5 / DESIGN §3.7、§1.4）。
 *
 * 钉住任务池这一侧的项目筛选契约：
 * 1. 项目位落在「来源之后、会议之前」，宽 148，选项来自唯一 `projectOptions` 投影
 *    （含停用项与「未指定项目」档，不是页面自己拼的）；
 * 2. `?project=12` / `?project=none` 深链进来即预置，请求原样带 `project`；
 * 3. chip 与「清除全部」同步，「未指定项目」chip 文案是「项目：未指定项目」；
 * 4. `PROJECT_INVALID`（手改 URL / 已删项目）→ 清空该项 + toast + 重取，不停在错误态；
 * 5. 项目筛选下为空时给专用空态，按钮只清项目条件。
 */
import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { createMemoryHistory, createRouter } from 'vue-router'
import type { TaskRow } from '@/types/api'

const mocks = vi.hoisted(() => ({ list: vi.fn(), counters: vi.fn(), options: vi.fn() }))

vi.mock('@/api/tasks', () => ({
  taskApi: {
    list: mocks.list,
    counters: mocks.counters,
    get: vi.fn(),
    start: vi.fn(),
    submit: vi.fn(),
    accept: vi.fn(),
    reject: vi.fn(),
    cancel: vi.fn(),
    restore: vi.fn(),
    forceComplete: vi.fn(),
    reassign: vi.fn(),
    reschedule: vi.fn(),
    urge: vi.fn(),
    create: vi.fn(),
    update: vi.fn(),
    events: vi.fn(),
    decisionItems: vi.fn().mockResolvedValue([]),
  },
}))

vi.mock('@/api/meta', () => ({ metaApi: { options: mocks.options } }))

const { ApiError } = await import('@/api/http')
const { useUiStore } = await import('@/stores/ui')
const TaskPoolPage = (await import('@/pages/TaskPoolPage.vue')).default

const PROJECTS = [
  { id: 9, name: '停用项目', enabled: false, sort: 2 },
  { id: 12, name: '老旧小区改造', enabled: true, sort: 1 },
]

const SAMPLE: TaskRow = {
  id: 11,
  title: '补齐上线检查清单',
  owner: { id: 7, name: '张三' },
  dueDate: '2026-11-01',
  source: {
    type: 'S20001',
    typeLabel: '客户投诉',
    form: 'PROJECT',
    projectId: 12,
    projectName: '老旧小区改造',
    projectEnabled: true,
  },
  status: 'PENDING',
  isOverdue: false,
  overdueDays: 0,
  dueSoon: false,
  urgeCount: 0,
  updatedAt: '2026-11-09T09:00:00+08:00',
}

async function mountAt(fullPath: string) {
  const pinia = createPinia()
  setActivePinia(pinia)
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/tasks', name: 'tasks', component: TaskPoolPage },
      { path: '/tasks/:id(\\d+)', name: 'task-detail', component: { template: '<div>详情</div>' } },
      { path: '/', name: 'home', component: { template: '<div>首页</div>' } },
    ],
  })
  await router.push(fullPath)
  await router.isReady()
  const wrapper = mount(TaskPoolPage, { global: { plugins: [router, pinia] } })
  await flushPromises()
  return { wrapper, router, pinia }
}

type Wrapper = Awaited<ReturnType<typeof mountAt>>['wrapper']

/** 项目位：唯一带 filter__project 的可搜索下拉（来源/会议仍是原生 select） */
function projectField(wrapper: Wrapper) {
  const field = wrapper.find('.filter__project')
  expect(field.exists(), '筛选条里有项目位').toBe(true)
  return field
}

async function openProjectList(wrapper: Wrapper): Promise<string[]> {
  const field = projectField(wrapper)
  await field.find('.field__control').trigger('mousedown')
  await flushPromises()
  return field.findAll('.list .option .option__text').map((item) => item.text())
}

async function pickProject(wrapper: Wrapper, label: string): Promise<void> {
  await openProjectList(wrapper)
  const field = projectField(wrapper)
  const option = field.findAll('.list .option').find((item) => item.text().includes(label))
  if (!option) throw new Error(`项目下拉里找不到：${label}`)
  await option.trigger('click')
  await flushPromises()
}

function lastListCall(): Record<string, unknown> {
  return (mocks.list.mock.calls.at(-1)?.[0] ?? {}) as Record<string, unknown>
}

function lastFocusListCall(): Record<string, unknown> {
  const call = mocks.list.mock.calls.at(-1)?.[0] as { page?: number } | undefined
  return (call ?? {}) as Record<string, unknown>
}

function waitDebounce(): Promise<void> {
  return new Promise((resolve) => setTimeout(resolve, 260))
}

beforeEach(() => {
  vi.clearAllMocks()
  mocks.options.mockResolvedValue({
    owners: [{ id: 7, name: '张三', role: 'OWNER', status: 'ACTIVE' }],
    meetings: [{ id: 2, title: '项目周会' }],
    sourceTypes: [],
    statuses: [],
    priorities: [],
    projects: PROJECTS,
  })
  mocks.list.mockResolvedValue({ items: [SAMPLE], total: 1, page: 1, size: 20 })
  mocks.counters.mockResolvedValue({ overdue: 0, pendingAccept: 0 })
})

describe('T08 任务池项目筛选位', () => {
  it('项目位在来源之后、会议之前，选项来自统一投影（停用带后缀、未指定独立一档）', async () => {
    const { wrapper } = await mountAt('/tasks')

    const row = wrapper.find('.filter__row')
    // AppSelect 把 aria-label 挂在根节点，AppSearchSelect 挂在 combobox 上：两处都取
    const order = [...row.element.children].map(
      (child) =>
        child.getAttribute('aria-label') ??
        child.querySelector('[aria-label]')?.getAttribute('aria-label') ??
        '',
    )
    expect(order).toEqual([
      '搜索标题',
      '状态筛选',
      '负责人筛选',
      '来源类型筛选',
      '项目筛选',
      '主题筛选',
      '来源会议筛选',
      '逾期筛选',
    ])

    expect(await openProjectList(wrapper)).toEqual([
      '全部项目',
      '老旧小区改造',
      '停用项目（已停用）',
      '未指定项目',
    ])
  })

  it('项目位宽 148（几何表 966 ≤ 1008，不参与伸缩）', () => {
    const source = readFileSync(
      resolve(process.cwd(), 'src/components/task/TaskFilterBar.vue'),
      'utf8',
    )
    expect(source).toMatch(/\.filter__project\s*\{[^}]*width:\s*148px/s)
  })

  it('?project=12 深链：请求带 project=12，chip 显示项目名', async () => {
    const { wrapper } = await mountAt('/tasks?project=12')

    expect(lastListCall()).toMatchObject({ project: '12' })
    expect(wrapper.text()).toContain('项目：老旧小区改造')
    const combobox = projectField(wrapper).find('input[role="combobox"]')
      .element as HTMLInputElement
    expect(combobox.value).toBe('老旧小区改造')
  })

  it('选「未指定项目」：请求带 project=none，URL 记成 none', async () => {
    const { wrapper, router } = await mountAt('/tasks')

    await pickProject(wrapper, '未指定项目')
    await waitDebounce()
    await flushPromises()

    expect(router.currentRoute.value.query.project).toBe('none')
    expect(lastListCall()).toMatchObject({ project: 'none' })
    expect(wrapper.text()).toContain('项目：未指定项目')
  })

  it('移除项目 chip：条件从 URL 与请求里一起消失', async () => {
    const { wrapper, router } = await mountAt('/tasks?project=12')
    expect(wrapper.text()).toContain('项目：老旧小区改造')

    const chip = wrapper.findAll('button.chip').find((item) => item.text().includes('项目：'))
    expect(chip, '项目 chip 存在').toBeTruthy()
    await chip!.trigger('click')
    await waitDebounce()
    await flushPromises()

    expect(router.currentRoute.value.query.project).toBeUndefined()
    expect(lastListCall().project).toBeUndefined()
    expect(wrapper.text()).not.toContain('项目：')
  })

  it('「清除全部」一并清空项目条件', async () => {
    const { wrapper, router } = await mountAt('/tasks?project=12&status=PENDING')

    const clear = wrapper.findAll('button').find((button) => button.text().includes('清除全部'))
    expect(clear).toBeTruthy()
    await clear!.trigger('click')
    await waitDebounce()
    await flushPromises()

    expect(router.currentRoute.value.query).toEqual({})
    expect(lastListCall().project).toBeUndefined()
  })

  it('未知项目（PROJECT_INVALID）：清空该条件 + toast + 按全部项目重取', async () => {
    mocks.list.mockRejectedValueOnce(
      new ApiError({ status: 400, code: 'PROJECT_INVALID', message: '项目已删除' }),
    )
    const { wrapper, router, pinia } = await mountAt('/tasks?project=999')
    expect(wrapper.text()).not.toContain('任务列表加载失败')

    expect(useUiStore(pinia).toasts.some((toast) => toast.message.includes('项目筛选已失效'))).toBe(
      true,
    )
    await waitDebounce()
    await flushPromises()
    expect(router.currentRoute.value.query.project).toBeUndefined()
    expect(lastListCall().project).toBeUndefined()
  })

  it('项目筛选下无结果：专用空态，按钮只清项目条件', async () => {
    mocks.list.mockResolvedValue({ items: [], total: 0, page: 1, size: 20 })
    const { wrapper, router } = await mountAt('/tasks?project=12&status=PENDING')

    expect(wrapper.text()).toContain('该项目下暂无任务')
    expect(wrapper.text()).toContain('换一个项目，或清除项目筛选')

    const clear = wrapper.findAll('button').find((button) => button.text().includes('清除项目筛选'))
    expect(clear).toBeTruthy()
    await clear!.trigger('click')
    await waitDebounce()
    await flushPromises()

    expect(router.currentRoute.value.query.project).toBeUndefined()
    expect(router.currentRoute.value.query.status).toBe('PENDING')
  })

  it('「未指定项目」筛选的空态文案与普通项目区分', async () => {
    mocks.list.mockResolvedValue({ items: [], total: 0, page: 1, size: 20 })
    const { wrapper } = await mountAt('/tasks?project=none')
    expect(wrapper.text()).toContain('未指定项目下暂无任务')
    expect(lastFocusListCall()).toMatchObject({ project: 'none' })
  })
})
