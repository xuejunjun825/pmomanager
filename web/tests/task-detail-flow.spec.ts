// @vitest-environment jsdom
/**
 * 详情页主路径（AC-9 / AC-10）：真实挂载页面，只替换网络层。
 * 覆盖三件事：
 * 1. 动作渲染只认服务端 permissions（不做前端角色推断）；
 * 2. 点「开始」→ 状态推进到进行中，主按钮换成「提交验收」（一条完整流转）；
 * 3. 409（别人先改了）→ 自动重取详情，界面不停在旧状态。
 */
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { createMemoryHistory, createRouter } from 'vue-router'
import { ApiError } from '@/api/http'
import type { TaskDetail } from '@/types/api'

const mocks = vi.hoisted(() => ({
  get: vi.fn(),
  start: vi.fn(),
  submit: vi.fn(),
}))

vi.mock('@/api/tasks', () => ({
  taskApi: {
    get: mocks.get,
    start: mocks.start,
    submit: mocks.submit,
    accept: vi.fn(),
    reject: vi.fn(),
    cancel: vi.fn(),
    restore: vi.fn(),
    forceComplete: vi.fn(),
    reassign: vi.fn(),
    reschedule: vi.fn(),
    urge: vi.fn(),
    list: vi.fn(),
    counters: vi.fn(),
    create: vi.fn(),
    update: vi.fn(),
    events: vi.fn(),
    decisionItems: vi.fn(),
  },
}))

vi.mock('@/api/meta', () => ({
  metaApi: { options: vi.fn().mockResolvedValue({ owners: [], meetings: [] }) },
}))

const TaskDetailPage = (await import('@/pages/TaskDetailPage.vue')).default

function detail(overrides: Partial<TaskDetail> = {}): TaskDetail {
  return {
    id: 5,
    title: '补齐上线检查清单',
    owner: { id: 7, name: '张三' },
    dueDate: '2026-11-12',
    source: { type: 'MEETING', meetingId: 2, meetingTitle: '项目周会', decisionItemId: 9, note: '周会第三条决议' },
    status: 'PENDING',
    isOverdue: false,
    overdueDays: 0,
    dueSoon: true,
    urgeCount: 0,
    updatedAt: '2026-11-09T09:00:00+08:00',
    dispatcher: { id: 1, name: '李四' },
    priority: 'MEDIUM',
    events: [
      {
        action: 'CREATE',
        actionLabel: '创建任务',
        actor: { id: 1, name: '李四' },
        toStatus: 'PENDING',
        createdAt: '2026-11-09T09:00:00+08:00',
      },
    ],
    permissions: {
      canStart: true,
      canSubmit: false,
      canAccept: false,
      canReject: false,
      canCancel: true,
      canRestore: false,
      canForceComplete: true,
      canReassign: true,
      canReschedule: true,
      canUrge: true,
      disabledReasons: {},
    },
    ...overrides,
  }
}

async function mountPage() {
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/tasks/:id(\\d+)', name: 'task-detail', component: TaskDetailPage },
      { path: '/tasks', name: 'tasks', component: { template: '<div>任务池</div>' } },
      { path: '/meetings/:id(\\d+)', name: 'meeting-detail', component: { template: '<div>会议</div>' } },
      { path: '/', name: 'home', component: { template: '<div>首页</div>' } },
      { path: '/404', component: { template: '<div>404</div>' } },
      { path: '/403', component: { template: '<div>403</div>' } },
    ],
  })
  await router.push('/tasks/5')
  await router.isReady()
  const wrapper = mount(TaskDetailPage, { global: { plugins: [router] } })
  await flushPromises()
  return { wrapper, router }
}

function buttonByText(wrapper: ReturnType<typeof mount>, text: string) {
  return wrapper.findAll('button').find((button) => button.text().includes(text))
}

describe('TaskDetailPage 主路径', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    mocks.get.mockReset()
    mocks.start.mockReset()
    mocks.submit.mockReset()
  })

  it('按服务端 permissions 渲染动作并完成一次「开始」流转', async () => {
    mocks.get.mockResolvedValue(detail())
    mocks.start.mockResolvedValue(
      detail({
        status: 'IN_PROGRESS',
        permissions: {
          ...detail().permissions,
          canStart: false,
          canSubmit: true,
        },
      }),
    )

    const { wrapper } = await mountPage()
    expect(wrapper.text()).toContain('补齐上线检查清单')
    expect(mocks.get).toHaveBeenCalledWith(5)

    const startButton = buttonByText(wrapper, '开始')
    expect(startButton).toBeTruthy()
    await startButton!.trigger('click')
    await flushPromises()

    expect(mocks.start).toHaveBeenCalledWith(5)
    expect(wrapper.text()).toContain('提交验收')
    expect(wrapper.find('.steps__item.is-current').text()).toContain('进行中')
  })

  it('409 时重取详情，状态与按钮都跟着服务端刷新', async () => {
    mocks.get
      .mockResolvedValueOnce(detail())
      .mockResolvedValueOnce(
        detail({
          status: 'IN_PROGRESS',
          permissions: { ...detail().permissions, canStart: false, canSubmit: true },
        }),
      )
    mocks.start.mockRejectedValue(
      new ApiError({ status: 409, code: 'TASK_STATUS_CONFLICT', message: '任务当前状态为「进行中」，不能再次开始' }),
    )

    const { wrapper } = await mountPage()
    await buttonByText(wrapper, '开始')!.trigger('click')
    await flushPromises()

    expect(mocks.get).toHaveBeenCalledTimes(2)
    expect(wrapper.text()).toContain('提交验收')
  })

  it('owner 访问他人任务（服务端 404）跳 404 页，而不是渲染空详情', async () => {
    mocks.get.mockRejectedValue(new ApiError({ status: 404, code: 'NOT_FOUND', message: '任务不存在' }))
    const { router } = await mountPage()
    await flushPromises()
    expect(router.currentRoute.value.path).toBe('/404')
  })
})
