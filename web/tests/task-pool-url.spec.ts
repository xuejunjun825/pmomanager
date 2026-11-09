// @vitest-environment jsdom
/**
 * 任务池筛选 ↔ URL 往返（AC-8）：从带参数的 URL 进入，请求参数必须原样还原；
 * 点「清除全部」后既清空 URL，也按无筛选重取一次。
 */
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { createMemoryHistory, createRouter } from 'vue-router'
import type { TaskRow } from '@/types/api'

const mocks = vi.hoisted(() => ({ list: vi.fn(), counters: vi.fn() }))

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
    decisionItems: vi.fn(),
  },
}))

vi.mock('@/api/meta', () => ({
  metaApi: {
    options: vi.fn().mockResolvedValue({
      owners: [{ id: 7, name: '张三', role: 'OWNER', status: 'ACTIVE' }],
      meetings: [{ id: 2, title: '项目周会' }],
      sourceTypes: [],
      statuses: [],
      priorities: [],
    }),
  },
}))

const TaskPoolPage = (await import('@/pages/TaskPoolPage.vue')).default

const sample: TaskRow = {
  id: 11,
  title: '补齐上线检查清单',
  owner: { id: 7, name: '张三' },
  dueDate: '2026-11-01',
  source: { type: 'MEETING', meetingId: 2, meetingTitle: '项目周会' },
  status: 'PENDING',
  isOverdue: true,
  overdueDays: 8,
  dueSoon: false,
  urgeCount: 0,
  updatedAt: '2026-11-09T09:00:00+08:00',
}

async function mountAt(fullPath: string) {
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
  const wrapper = mount(TaskPoolPage, { global: { plugins: [router] } })
  await flushPromises()
  return { wrapper, router }
}

describe('TaskPoolPage 筛选与 URL', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    mocks.list.mockReset()
    mocks.counters.mockReset()
    mocks.list.mockResolvedValue({ items: [sample], total: 45, page: 2, size: 20 })
    mocks.counters.mockResolvedValue({ overdue: 7, pendingAccept: 5 })
  })

  it('刷新带参数的 URL：请求参数与汇总行都还原', async () => {
    const { wrapper } = await mountAt('/tasks?status=PENDING&owner=7&overdue=true&page=2')

    expect(mocks.list).toHaveBeenCalledWith(
      expect.objectContaining({ status: 'PENDING', ownerId: 7, overdue: true, page: 2, size: 20 }),
    )
    expect(wrapper.text()).toContain('共 45 条')
    expect(wrapper.text()).toContain('逾期 7')
    expect(wrapper.text()).toContain('逾期 8 天')
  })

  it('点「清除全部」清空 URL 并按无筛选重取', async () => {
    const { wrapper, router } = await mountAt('/tasks?status=PENDING&owner=7')

    const clear = wrapper.findAll('button').find((button) => button.text().includes('清除全部'))
    expect(clear).toBeTruthy()
    await clear!.trigger('click')
    await new Promise((resolve) => setTimeout(resolve, 260))
    await flushPromises()

    expect(router.currentRoute.value.query).toEqual({})
    expect(mocks.list).toHaveBeenLastCalledWith(expect.objectContaining({ page: 1, size: 20 }))
    const lastCall = mocks.list.mock.calls.at(-1)?.[0] as Record<string, unknown>
    expect(lastCall.status).toBeUndefined()
    expect(lastCall.ownerId).toBeUndefined()
  })
})
