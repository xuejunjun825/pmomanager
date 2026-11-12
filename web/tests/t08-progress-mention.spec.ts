// @vitest-environment jsdom
/**
 * T08 任务详情进度与通知中心前端（AC-7/8/9/10/11/13）。
 *
 * 钉住四类最容易「看起来对、实际错」的接缝：
 * - @ 只认选择器：手打「@姓名」不产生结构化提及、不高亮、不发通知；
 * - 200 条上限只禁用不隐藏；500 字计数按 code point 计；
 * - 合并时间线按 createdAt 倒序（同秒按 id 倒序），进度条目 id=progress-{id} 可被深链定位；
 * - 通知「@我的」走服务端 type 过滤，点击先已读再跳 #progress-{id}。
 */
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { createMemoryHistory, createRouter } from 'vue-router'
import { ApiError } from '@/api/http'
import type { ProgressRow, TaskDetail, UserOption } from '@/types/api'
import type { NotifyType } from '@/types/enums'

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
  metaApi: { options: vi.fn().mockResolvedValue({ owners: [], meetings: [], sourceTypes: [] }) },
}))

const notifyMocks = vi.hoisted(() => ({
  list: vi.fn(),
  unreadCount: vi.fn(),
  markRead: vi.fn(),
  markAllRead: vi.fn(),
}))

vi.mock('@/api/notifications', () => ({ notificationsApi: notifyMocks }))

const usersApiMock = vi.hoisted(() => ({ options: vi.fn().mockResolvedValue([]) }))
vi.mock('@/api/users', () => ({ usersApi: usersApiMock }))

import MentionPicker from '@/components/task/MentionPicker.vue'
import ProgressComposer from '@/components/task/ProgressComposer.vue'
import ProgressTimeline from '@/components/task/ProgressTimeline.vue'
import TaskDetailPage from '@/pages/TaskDetailPage.vue'
import NotificationsPage from '@/pages/NotificationsPage.vue'
import { useNotificationsStore } from '@/stores/notifications'

const SELF = 1

const USERS: UserOption[] = [
  { id: 1, name: '张三', role: 'ADMIN', status: 'ACTIVE' },
  { id: 2, name: '李四', role: 'OWNER', status: 'ACTIVE' },
  { id: 3, name: '王五', role: 'OWNER', status: 'DISABLED' },
  { id: 4, name: '赵六', role: 'EXEC', status: 'ACTIVE' },
]

function row(over: Partial<ProgressRow> = {}): ProgressRow {
  return {
    id: 12,
    content: '客户已确认口径，等合同盖章。',
    author: { id: 8, name: '张三' },
    mentions: [],
    mentionedMe: false,
    createdAt: '2026-11-09T15:20:00+08:00',
    ...over,
  }
}

function detail(over: Partial<TaskDetail> = {}): TaskDetail {
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
      canAddProgress: true,
      disabledReasons: {},
    },
    ...over,
  }
}

async function mountPage<T>(component: T, path: string) {
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/tasks/:id(\\d+)', component: TaskDetailPage },
      { path: '/tasks', component: { template: '<div>任务池</div>' } },
      { path: '/meetings/:id(\\d+)', component: { template: '<div>会议</div>' } },
      { path: '/notifications', component: NotificationsPage },
      { path: '/', component: { template: '<div>首页</div>' } },
      { path: '/404', component: { template: '<div>404</div>' } },
      { path: '/403', component: { template: '<div>403</div>' } },
    ],
  })
  await router.push(path)
  await router.isReady()
  const pinia = createPinia()
  setActivePinia(pinia)
  const wrapper = mount(component as never, {
    attachTo: document.body,
    global: { plugins: [router, pinia] },
  })
  await flushPromises()
  return { wrapper, router, pinia }
}

beforeEach(() => {
  setActivePinia(createPinia())
  vi.clearAllMocks()
  taskMocks.progress.mockResolvedValue({ items: [], total: 0, hasMore: false })
  taskMocks.get.mockResolvedValue(detail())
  notifyMocks.markRead.mockResolvedValue(undefined)
})

describe('MentionPicker', () => {
  it('排除自己与停用账号，本地搜索姓名，选中/取消都只发 id', async () => {
    const wrapper = mount(MentionPicker, {
      props: { modelValue: [], users: USERS, selfId: SELF },
    })

    const names = wrapper.findAll('.picker__name').map((node) => node.text())
    expect(names).toEqual(['李四', '赵六'])
    expect(wrapper.text()).not.toContain('王五')

    await wrapper.find('input').setValue('赵')
    expect(wrapper.findAll('.picker__option')).toHaveLength(1)

    await wrapper.find('.picker__option').trigger('click')
    expect(wrapper.emitted('update:modelValue')?.[0]).toEqual([[4]])
    expect(wrapper.emitted('pick')?.[0]?.[0]).toMatchObject({ id: 4, name: '赵六' })
  })

  it('10 人上限：其余行禁用并给出可见原因，已选行仍可取消', async () => {
    const many: UserOption[] = Array.from({ length: 11 }, (_, index) => ({
      id: 100 + index,
      name: `成员${index}`,
      role: 'OWNER' as const,
      status: 'ACTIVE' as const,
    }))
    const wrapper = mount(MentionPicker, {
      props: { modelValue: many.slice(0, 10).map((user) => user.id), users: many },
    })

    const options = wrapper.findAll('.picker__option')
    expect(options).toHaveLength(11)
    const unselected = options.find((option) => !option.classes('is-selected'))
    expect(unselected?.attributes('disabled')).toBeDefined()
    expect(wrapper.text()).toContain('10 人已选满，取消一位后可继续')

    await options[0].trigger('click')
    expect(wrapper.emitted('update:modelValue')?.[0]?.[0]).toHaveLength(9)
  })
})

describe('ProgressComposer', () => {
  function mountComposer(total = 0) {
    return mount(ProgressComposer, {
      props: { taskId: 5, total, users: USERS, selfId: SELF },
    })
  }

  it('按 code point 计数；空内容不能提交', async () => {
    const wrapper = mountComposer()
    await wrapper.find('textarea').setValue('😀 已联系客户')
    expect(wrapper.find('.composer__count').text()).toBe('7/500')

    const submit = wrapper.findAll('button').find((button) => button.text() === '提交')
    expect(submit?.attributes('disabled')).toBeUndefined()

    await wrapper.find('textarea').setValue('   ')
    expect(submit?.attributes('disabled')).toBeDefined()
  })

  it('200 条上限时输入区保留可见但禁用，原因是可见文本', () => {
    const wrapper = mountComposer(200)
    const textarea = wrapper.find('textarea')
    expect(textarea.exists()).toBe(true)
    expect(textarea.attributes('disabled')).toBeDefined()
    expect(textarea.attributes('placeholder')).toContain('200 条上限')
    expect(wrapper.text()).toContain('该任务进度已达 200 条上限')
  })

  it('提交带上选择器选中的成员，成功后 emit created 并清空输入', async () => {
    const created = row({ id: 21, content: '已联系客户' })
    taskMocks.addProgress.mockResolvedValue(created)
    const wrapper = mountComposer()

    await wrapper.find('textarea').setValue('已联系客户')
    await wrapper.find('.composer__mention').trigger('click')
    await wrapper.findAll('.picker__option')[0].trigger('click')
    await wrapper.findAll('button').find((button) => button.text() === '提交')!.trigger('click')
    await flushPromises()

    expect(taskMocks.addProgress).toHaveBeenCalledTimes(1)
    expect(taskMocks.addProgress.mock.calls[0][0]).toBe(5)
    expect(taskMocks.addProgress.mock.calls[0][1]).toEqual({
      content: '已联系客户 @李四',
      mentionedUserIds: [2],
    })
    expect(wrapper.emitted('created')?.[0]?.[0]).toMatchObject({ id: 21 })
    expect((wrapper.find('textarea').element as HTMLTextAreaElement).value).toBe('')
  })

  it('403 时整块换成「你没有补充进度的权限」', async () => {
    taskMocks.addProgress.mockRejectedValue(
      new ApiError({ status: 403, code: 'PROGRESS_FORBIDDEN', message: '没有权限' }),
    )
    const wrapper = mountComposer()
    await wrapper.find('textarea').setValue('补一条')
    await wrapper.findAll('button').find((button) => button.text() === '提交')!.trigger('click')
    await flushPromises()

    expect(wrapper.text()).toContain('你没有补充进度的权限')
    expect(wrapper.find('textarea').exists()).toBe(false)
  })
})

describe('ProgressTimeline', () => {
  it('合并倒序、同秒按 id 倒序；进度项带锚点 id 与「进度」chip', () => {
    const wrapper = mount(ProgressTimeline, {
      props: {
        progress: [
          row({ id: 12, createdAt: '2026-11-09T15:20:00+08:00' }),
          row({ id: 13, createdAt: '2026-11-09T15:20:00+08:00' }),
          row({ id: 10, createdAt: '2026-11-08T10:00:00+08:00', content: '更早的一条' }),
        ],
        events: [
          {
            action: 'START',
            actionLabel: '开始',
            actor: { id: 2, name: '李四' },
            fromStatus: 'PENDING',
            toStatus: 'IN_PROGRESS',
            createdAt: '2026-11-09T16:20:00+08:00',
          },
        ],
      },
    })

    const items = wrapper.findAll('.tl__item')
    expect(items[0].text()).toContain('开始')
    expect(items[1].attributes('id')).toBe('progress-13')
    expect(items[2].attributes('id')).toBe('progress-12')
    expect(items[3].attributes('id')).toBe('progress-10')
    expect(items[1].attributes('tabindex')).toBe('-1')
    expect(items[1].find('.tl__chip').text()).toBe('进度')
  })

  it('只高亮选择器选中的提及，手打 @姓名 保持普通文本', () => {
    const wrapper = mount(ProgressTimeline, {
      props: {
        progress: [
          row({ id: 12, content: '同意，@李四 跟进', mentions: [{ id: 2, name: '李四' }] }),
          row({ id: 10, content: '手打 @王五 不算', mentions: [] }),
        ],
        events: [],
      },
    })

    const first = wrapper.find('#progress-12')
    expect(first.find('.tl__mention').text()).toBe('@李四')
    const second = wrapper.find('#progress-10')
    expect(second.find('.tl__mention').exists()).toBe(false)
    expect(second.text()).toContain('@王五')
  })

  it('空列表给「任务刚创建」而不是空白', () => {
    const wrapper = mount(ProgressTimeline, { props: { progress: [], events: [] } })
    expect(wrapper.text()).toContain('任务刚创建，还没有流转或进度记录')
  })
})

describe('TaskDetailPage 进度与深链', () => {
  it('#progress-12 定位后加 is-anchor，进度条来自接口', async () => {
    taskMocks.progress.mockResolvedValue({
      items: [row({ id: 12 }), row({ id: 10, createdAt: '2026-11-08T10:00:00+08:00' })],
      total: 2,
      hasMore: false,
    })

    const { wrapper } = await mountPage(TaskDetailPage, '/tasks/5#progress-12')
    const anchor = wrapper.find('#progress-12')
    expect(anchor.exists()).toBe(true)
    expect(anchor.classes()).toContain('is-anchor')
    expect(wrapper.text()).toContain('流转记录')
    expect(wrapper.find('.panel__count').text()).toContain('2/200')
  })

  it('mentionOnly：整组隐藏状态动作，显示被 @ 提示条，进度输入仍可用', async () => {
    taskMocks.get.mockResolvedValue(
      detail({ permissions: { ...detail().permissions, mentionOnly: true, canStart: false } }),
    )
    taskMocks.progress.mockResolvedValue({
      items: [row({ id: 12, mentionedMe: true, author: { id: 8, name: '王五' } })],
      total: 1,
      hasMore: false,
    })

    const { wrapper } = await mountPage(TaskDetailPage, '/tasks/5')
    expect(wrapper.text()).toContain('你被 王五 @ 过')
    expect(wrapper.findAll('.head__actions button')).toHaveLength(0)
    expect(wrapper.find('.composer textarea').exists()).toBe(true)
  })
})

describe('NotificationsPage @我的', () => {
  function notification(over: Record<string, unknown> = {}) {
    return {
      id: 1,
      type: 'MENTION' as NotifyType,
      typeLabel: '被@',
      taskId: 7,
      taskTitle: '季度复盘材料',
      ownerName: '李负责',
      createdAt: '2026-11-09T09:30:00+08:00',
      channelStatus: 'SENT',
      channelLabel: '钉钉已送达',
      read: false,
      retryCount: 0,
      progressId: 33,
      ...over,
    }
  }

  it('「@我的」按 type=MENTION 查询，点击先已读再跳 #progress-{id}', async () => {
    notifyMocks.list.mockImplementation((query: { type?: string }) =>
      Promise.resolve({
        items: [notification()],
        total: 1,
        page: 1,
        size: 20,
        unreadCount: 1,
        ...(query?.type === 'MENTION' ? {} : {}),
      }),
    )

    const { wrapper, router, pinia } = await mountPage(NotificationsPage, '/notifications')
    const store = useNotificationsStore(pinia)
    const tabs = wrapper.findAll('.tabs__item')
    expect(tabs.map((node) => node.text())).toEqual(['全部 1', '@我的 1', '未读 1'])

    await tabs[1].trigger('click')
    await flushPromises()
    expect(notifyMocks.list).toHaveBeenLastCalledWith({
      unreadOnly: false,
      page: 1,
      size: 20,
      type: 'MENTION',
    })
    expect(wrapper.find('.notice__type--mention').text()).toBe('被@')

    await wrapper.find('.notice__hit').trigger('click')
    await flushPromises()
    expect(notifyMocks.markRead).toHaveBeenCalledWith(1)
    expect(store.unreadCount).toBe(0)
    expect(router.currentRoute.value.fullPath).toBe('/tasks/7#progress-33')
  })
})
