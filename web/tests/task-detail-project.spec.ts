// @vitest-environment jsdom
/**
 * 详情页项目位（r4 T09 / AC-7、AC-8）。
 *
 * 真实挂载页面，只替身网络层。钉住四件事：
 * 1. 已绑项目：项目名完整可读（20 字不截断），链接指向 `/tasks?project=<id>`；
 * 2. 项目已停用：后缀「（已停用）」在链接内，仍可下钻（历史事实不因停用消失）；
 * 3. 改形历史（form=PROJECT 且无项目 id）：渲染不可点的「未指定项目」；
 * 4. 会议型 / 说明型来源不产生任何项目元素。
 */
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { createMemoryHistory, createRouter } from 'vue-router'
import type { TaskDetail } from '@/types/api'

const mocks = vi.hoisted(() => ({ get: vi.fn() }))

vi.mock('@/api/tasks', async (importOriginal) => {
  const actual = await importOriginal<typeof import('@/api/tasks')>()
  return { ...actual, taskApi: { ...actual.taskApi, get: mocks.get } }
})
vi.mock('@/api/meta', async (importOriginal) => {
  const actual = await importOriginal<typeof import('@/api/meta')>()
  return {
    ...actual,
    metaApi: {
      options: vi.fn().mockResolvedValue({ owners: [], meetings: [], sourceTypes: [], projects: [] }),
    },
  }
})

const TaskDetailPage = (await import('@/pages/TaskDetailPage.vue')).default

/** 20 字项目名（名称上限），用来验证详情不截断（AC-7 的硬要求） */
const LONG_NAME = '老旧小区综合改造提升工程第二批施工总承包'

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
    events: [],
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

async function mountPage(): Promise<ReturnType<typeof mount>> {
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
  return wrapper
}

describe('TaskDetailPage 项目位', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    mocks.get.mockReset()
  })

  it('已绑项目：名称完整展示，链接指向任务池并预置 project 筛选', async () => {
    mocks.get.mockResolvedValue(
      detail({
        source: { type: 'PROJECT', form: 'PROJECT', projectId: 12, projectName: LONG_NAME, projectEnabled: true },
      }),
    )
    const wrapper = await mountPage()

    const link = wrapper.find('a.source__project')
    expect(link.exists()).toBe(true)
    // 20 字原样输出（不截断、不加省略号、不带停用后缀）
    expect(LONG_NAME.length).toBe(20)
    expect(link.text()).toBe(LONG_NAME)
    const href = link.attributes('href') ?? ''
    expect(href).toContain('/tasks?project=12')
    // 不可点的「未指定项目」不应同时出现
    expect(wrapper.find('.tag--unspecified').exists()).toBe(false)
  })

  it('项目已停用：后缀在链接内，仍可下钻', async () => {
    mocks.get.mockResolvedValue(
      detail({
        source: { type: 'PROJECT', form: 'PROJECT', projectId: 12, projectName: '旧城改造', projectEnabled: false },
      }),
    )
    const wrapper = await mountPage()

    const link = wrapper.find('a.source__project')
    expect(link.exists()).toBe(true)
    expect(link.text()).toContain('旧城改造')
    expect(link.text()).toContain('（已停用）')
    expect(link.attributes('href') ?? '').toContain('/tasks?project=12')
  })

  it('改形历史（形态为项目但无项目）：渲染不可点的「未指定项目」', async () => {
    mocks.get.mockResolvedValue(
      detail({ source: { type: 'PROJECT', form: 'PROJECT', projectId: null, note: '改形前创建的说明' } }),
    )
    const wrapper = await mountPage()

    expect(wrapper.find('a.source__project').exists()).toBe(false)
    const tag = wrapper.find('.tag--unspecified')
    expect(tag.exists()).toBe(true)
    expect(tag.text()).toContain('未指定项目')
  })

  it('会议型 / 说明型来源不渲染任何项目元素', async () => {
    mocks.get.mockResolvedValue(detail())
    const meeting = await mountPage()
    expect(meeting.find('a.source__project').exists()).toBe(false)
    expect(meeting.find('.tag--unspecified').exists()).toBe(false)

    mocks.get.mockResolvedValue(detail({ source: { type: 'NOTE', form: 'NOTE', note: '临时说明' } }))
    const note = await mountPage()
    expect(note.find('a.source__project').exists()).toBe(false)
    expect(note.find('.tag--unspecified').exists()).toBe(false)
  })
})
