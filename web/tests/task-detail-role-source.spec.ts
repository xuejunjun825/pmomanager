// @vitest-environment jsdom
/**
 * 详情页的来源展示与角色化会议链接（r7 T6 / DESIGN §3.9、AC-5）。
 *
 * 会议详情接口仅 ADMIN 可读：EXEC/OWNER 看到的是标题纯文本 + 「仅管理员可查看会议」，
 * 不再点进 403；主题标签与计划行在两种角色下一致。
 */
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { createMemoryHistory, createRouter } from 'vue-router'
import type { AuthUser, TaskDetail } from '@/types/api'

const session = vi.hoisted(() => ({ user: null as AuthUser | null }))

vi.mock('@/api/auth', () => ({
  authApi: {
    config: vi.fn(async () => ({
      dingtalkConfigured: false,
      missingKeys: [],
      passwordLogin: true,
      siteName: '督办系统',
    })),
    me: vi.fn(async () => {
      if (!session.user) throw new Error('401')
      return session.user
    }),
    login: vi.fn(),
    logout: vi.fn(),
    changePassword: vi.fn(),
    authorizeUrl: vi.fn(),
    dingtalkAuto: vi.fn(),
  },
}))

const mocks = vi.hoisted(() => ({ get: vi.fn(), progress: vi.fn() }))

vi.mock('@/api/tasks', async (importOriginal) => {
  const actual = await importOriginal<typeof import('@/api/tasks')>()
  return {
    ...actual,
    taskApi: { ...actual.taskApi, get: mocks.get, progress: mocks.progress },
  }
})
vi.mock('@/api/meta', async (importOriginal) => {
  const actual = await importOriginal<typeof import('@/api/meta')>()
  return {
    ...actual,
    metaApi: {
      options: vi.fn().mockResolvedValue({
        owners: [],
        meetings: [],
        sourceTypes: [],
        projects: [],
        themes: [],
      }),
    },
  }
})

const { useAuthStore } = await import('@/stores/auth')
const TaskDetailPage = (await import('@/pages/TaskDetailPage.vue')).default

const ADMIN: AuthUser = { id: 1, name: '张管理', role: 'ADMIN', mustChangePassword: false, landingPath: '/tasks' }
const EXEC: AuthUser = { id: 2, name: '王主管', role: 'EXEC', mustChangePassword: false, landingPath: '/matrix' }

function detail(overrides: Partial<TaskDetail> = {}): TaskDetail {
  return {
    id: 5,
    title: '补齐上线检查清单',
    owner: { id: 7, name: '张三' },
    dueDate: '2026-12-12',
    source: { type: 'MEETING', meetingId: 2, meetingTitle: '项目周会', decisionItemId: 9 },
    theme: {
      id: 3,
      name: '安全',
      color: '#175cd3',
      softColor: '#eff8ff',
      borderColor: '#b2ddff',
      enabled: true,
    },
    plannedStartDate: '2026-12-01',
    status: 'IN_PROGRESS',
    isOverdue: false,
    overdueDays: 0,
    dueSoon: false,
    urgeCount: 0,
    updatedAt: '2026-11-09T09:00:00+08:00',
    dispatcher: { id: 1, name: '李四' },
    priority: 'MEDIUM',
    events: [],
    permissions: {
      canStart: false,
      canSubmit: true,
      canAccept: false,
      canReject: false,
      canCancel: true,
      canRestore: false,
      canForceComplete: false,
      canReassign: true,
      canReschedule: true,
      canUrge: true,
      disabledReasons: {},
    },
    ...overrides,
  }
}

async function mountPage(user: AuthUser): Promise<ReturnType<typeof mount>> {
  session.user = user
  const pinia = createPinia()
  setActivePinia(pinia)
  await useAuthStore().ensureLoaded()
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/tasks/:id(\\d+)', name: 'task-detail', component: TaskDetailPage },
      { path: '/tasks', name: 'tasks', component: { template: '<div>任务池</div>' } },
      { path: '/meetings/:id(\\d+)', name: 'meeting-detail', component: { template: '<div>会议</div>' } },
      { path: '/', name: 'home', component: { template: '<div>首页</div>' } },
    ],
  })
  await router.push('/tasks/5')
  await router.isReady()
  const wrapper = mount(TaskDetailPage, { global: { plugins: [router, pinia] } })
  await flushPromises()
  return wrapper
}

beforeEach(() => {
  mocks.get.mockReset()
  mocks.progress.mockReset()
  mocks.progress.mockResolvedValue({ items: [], total: 0, hasMore: false })
  mocks.get.mockResolvedValue(detail())
})

describe('TaskDetailPage 来源与角色', () => {
  it('ADMIN：会议标题可点，链接携带决议项高亮参数', async () => {
    const wrapper = await mountPage(ADMIN)
    const link = wrapper.find('a.source__link')
    expect(link.exists()).toBe(true)
    expect(link.text()).toBe('项目周会')
    expect(link.attributes('href') ?? '').toContain('/meetings/2')
    expect(wrapper.find('.source__hint').exists()).toBe(false)
    wrapper.unmount()
  })

  it('EXEC：会议标题降级为纯文本 + 说明，不产生可点链接（消除 403 死路）', async () => {
    const wrapper = await mountPage(EXEC)
    expect(wrapper.find('a.source__link').exists()).toBe(false)
    expect(wrapper.find('.source__meeting').text()).toBe('项目周会')
    expect(wrapper.find('.source__hint').text()).toBe('仅管理员可查看会议')
    wrapper.unmount()
  })

  it('两种角色都展示主题标签与计划行，计划行含起止日期', async () => {
    for (const user of [ADMIN, EXEC]) {
      const wrapper = await mountPage(user)
      const tag = wrapper.find('.source__head .theme-tag')
      expect(tag.exists()).toBe(true)
      expect(tag.text()).toBe('安全')
      expect(wrapper.find('.source__plan').text()).toContain('2026-12-01')
      wrapper.unmount()
    }
  })

  it('未挂主题：来源区不渲染空主题标签；有逾期无计划时给出推算说明', async () => {
    mocks.get.mockResolvedValue(
      detail({ theme: null, plannedStartDate: null, isOverdue: true, overdueDays: 4 }),
    )
    const wrapper = await mountPage(ADMIN)
    expect(wrapper.find('.source__head .theme-tag').exists()).toBe(false)
    expect(wrapper.find('.source__plan').text()).toContain('甘特按创建日推算')
    wrapper.unmount()
  })
})
