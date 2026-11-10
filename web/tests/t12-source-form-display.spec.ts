// @vitest-environment jsdom
/**
 * 来源形态展示（AC-5 / D-01 回归门禁）：
 * 自定义「需绑定会议」形态来源的编码不是内置 MEETING（如 S00042），
 * 详情页与任务池必须按「是否绑定会议」渲染会议链接/标题，而不是按来源编码判断。
 *
 * 追加门禁（r3 / A-01）：详情页来源区只由 SourceTag 渲染一次，
 * 不得再出现与标签并列的第二份来源文字（「会议决议 会议决议」），
 * 且 source 缺失时来源区以「—」占位而不是留白。
 */
import { describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { createMemoryHistory, createRouter } from 'vue-router'

const taskMocks = vi.hoisted(() => ({
  get: vi.fn(),
  progress: vi.fn(),
  addProgress: vi.fn(),
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
  list: vi.fn(),
  counters: vi.fn(),
  create: vi.fn(),
  update: vi.fn(),
  events: vi.fn(),
  decisionItems: vi.fn(),
}))

vi.mock('@/api/tasks', () => ({ taskApi: taskMocks }))
vi.mock('@/api/meta', () => ({
  metaApi: {
    options: vi.fn().mockResolvedValue({
      owners: [],
      meetings: [],
      sourceTypes: [
        {
          value: 'S00042',
          label: '客户评审会',
          color: '#067647',
          softColor: '#E7F6EC',
          borderColor: '#B7E4C7',
          form: 'MEETING',
          enabled: true,
          sort: 3,
        },
      ],
    }),
  },
}))
vi.mock('@/api/notifications', () => ({
  notificationsApi: {
    list: vi.fn().mockResolvedValue({ items: [], total: 0, unreadCount: 0 }),
    unreadCount: vi.fn().mockResolvedValue({ unreadCount: 0 }),
    markRead: vi.fn(),
    markAllRead: vi.fn(),
  },
}))
vi.mock('@/api/users', () => ({ usersApi: { options: vi.fn().mockResolvedValue([]) } }))

import TaskDetailPage from '@/pages/TaskDetailPage.vue'
import TaskTable from '@/components/task/TaskTable.vue'
import type { TaskRow, TaskSource } from '@/types/api'

const customMeetingSource = {
  type: 'S00042',
  typeLabel: '客户评审会',
  color: '#067647',
  softColor: '#E7F6EC',
  borderColor: '#B7E4C7',
  meetingId: 9,
  meetingTitle: '客户评审会 A',
  decisionItemId: 3,
  note: '',
}

const detailPayload = {
  id: 5,
  title: '客户评审会待办',
  owner: { id: 7, name: '张三' },
  dueDate: '2026-11-12',
  source: customMeetingSource as TaskSource | null,
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
    canAddProgress: true,
    disabledReasons: {},
  },
}

const routes = [
  { path: '/tasks/:id(\\d+)', component: TaskDetailPage },
  { path: '/tasks', component: { template: '<div>任务池</div>' } },
  { path: '/meetings/:id(\\d+)', component: { template: '<div>会议</div>' } },
  { path: '/notifications', component: { template: '<div>通知</div>' } },
  { path: '/', component: { template: '<div>首页</div>' } },
]

async function mountDetail(source: TaskSource | null) {
  setActivePinia(createPinia())
  vi.clearAllMocks()
  taskMocks.progress.mockResolvedValue({ items: [], total: 0, hasMore: false })
  taskMocks.get.mockResolvedValue({ ...detailPayload, source })

  const router = createRouter({ history: createMemoryHistory(), routes })
  await router.push('/tasks/5')
  await router.isReady()

  const wrapper = mount(TaskDetailPage, {
    attachTo: document.body,
    global: { plugins: [router, createPinia()] },
  })
  await flushPromises()
  return wrapper
}

/** 来源名在来源区文本里出现的次数（R-1/A-01：只允许 1 次） */
function occurrences(text: string, needle: string): number {
  return text.split(needle).length - 1
}

describe('来源形态展示不按来源编码判断（D-01）', () => {
  it('任务池：自定义会议形态来源显示会议标题', () => {
    const row: TaskRow = {
      id: 5,
      title: '客户评审会待办',
      owner: { id: 7, name: '张三' },
      dueDate: '2026-11-12',
      source: customMeetingSource,
      status: 'PENDING',
      isOverdue: false,
      overdueDays: 0,
      dueSoon: true,
      urgeCount: 0,
      updatedAt: '2026-11-09T09:00:00+08:00',
    }
    const wrapper = mount(TaskTable, { props: { items: [row] } })
    expect(wrapper.text()).toContain('客户评审会 A')
  })

  it('详情：自定义会议形态来源渲染 /meetings/:id 链接与会议标题', async () => {
    const wrapper = await mountDetail(customMeetingSource)

    expect(wrapper.html()).toContain('/meetings/9')
    expect(wrapper.text()).toContain('客户评审会 A')
    wrapper.unmount()
  })

  it('详情：来源名只渲染一次（SourceTag 为唯一渲染点）', async () => {
    const wrapper = await mountDetail(customMeetingSource)

    const head = wrapper.find('.source__head')
    expect(head.exists()).toBe(true)
    expect(occurrences(head.text(), '客户评审会')).toBe(1)
    expect(head.findAll('.tag')).toHaveLength(1)
    wrapper.unmount()
  })

  it('详情：source 缺失时来源区以「—」占位，不留白', async () => {
    const wrapper = await mountDetail(null)

    const head = wrapper.find('.source__head')
    expect(head.exists()).toBe(true)
    expect(head.text().trim()).toBe('—')
    expect(head.find('.tag').exists()).toBe(false)
    wrapper.unmount()
  })
})
