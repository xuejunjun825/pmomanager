// @vitest-environment jsdom
/**
 * 任务池主题筛选与行内归主题（r7 T6 / DESIGN §3.8）。
 *
 * 钉住四件事：`?theme=3` 深链原样带给后端、chip 可移除、ADMIN 行内下拉直接提交
 * `{ themeId }` 并局部更新、非管理员只看标签（不渲染第二个编辑入口）。
 */
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { createMemoryHistory, createRouter } from 'vue-router'
import type { AuthUser, TaskRow } from '@/types/api'

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

const mocks = vi.hoisted(() => ({
  list: vi.fn(),
  counters: vi.fn(),
  options: vi.fn(),
  update: vi.fn(),
}))

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
    update: mocks.update,
    events: vi.fn(),
    decisionItems: vi.fn().mockResolvedValue([]),
  },
}))

vi.mock('@/api/meta', () => ({ metaApi: { options: mocks.options } }))

const { useAuthStore } = await import('@/stores/auth')
const TaskPoolPage = (await import('@/pages/TaskPoolPage.vue')).default

const ADMIN: AuthUser = { id: 1, name: '张管理', role: 'ADMIN', mustChangePassword: false, landingPath: '/tasks' }

const THEMES = [
  { id: 2, name: '交付', color: '#067647', softColor: '#ecfdf3', borderColor: '#abefc6', sort: 2, enabled: true },
  { id: 3, name: '安全', color: '#175cd3', softColor: '#eff8ff', borderColor: '#b2ddff', sort: 1, enabled: true },
]

const SAMPLE: TaskRow = {
  id: 11,
  title: '补齐上线检查清单',
  owner: { id: 7, name: '张三' },
  dueDate: '2026-11-01',
  source: { type: 'MEETING', meetingId: 2, meetingTitle: '项目周会' },
  status: 'PENDING',
  isOverdue: false,
  overdueDays: 0,
  dueSoon: false,
  urgeCount: 0,
  updatedAt: '2026-11-09T09:00:00+08:00',
  theme: { id: 3, name: '安全', color: '#175cd3', softColor: '#eff8ff', borderColor: '#b2ddff', enabled: true },
}

async function mountAt(fullPath: string, role: AuthUser | null = ADMIN) {
  session.user = role
  const pinia = createPinia()
  setActivePinia(pinia)
  const auth = useAuthStore()
  await auth.ensureLoaded()
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
  return { wrapper, router }
}

function lastListCall(): Record<string, unknown> {
  return (mocks.list.mock.calls.at(-1)?.[0] ?? {}) as Record<string, unknown>
}

function waitDebounce(): Promise<void> {
  // load() 走 220ms 防抖，断言请求参数前必须等它落定
  return new Promise((resolve) => setTimeout(resolve, 280))
}

beforeEach(() => {
  vi.clearAllMocks()
  mocks.options.mockResolvedValue({
    owners: [{ id: 7, name: '张三', role: 'OWNER', status: 'ACTIVE' }],
    meetings: [{ id: 2, title: '项目周会' }],
    sourceTypes: [],
    statuses: [],
    priorities: [],
    projects: [],
    themes: THEMES,
  })
  mocks.list.mockResolvedValue({ items: [SAMPLE], total: 1, page: 1, size: 20 })
  mocks.counters.mockResolvedValue({ total: 1, unfinished: 1, overdue: 0, dueSoon24h: 0, pendingAccept: 0, done: 0 })
  mocks.update.mockImplementation(async (id: number, body: { themeId: number }) => ({
    ...SAMPLE,
    id,
    theme: body.themeId === 0 ? null : THEMES.find((item) => item.id === body.themeId),
  }))
})

describe('TaskPoolPage 主题筛选', () => {
  it('?theme=3 深链：请求带 theme，筛选条回显主题，chip 可移除', async () => {
    const { wrapper } = await mountAt('/tasks?theme=3')
    expect(lastListCall()).toMatchObject({ theme: '3' })
    expect((wrapper.find('.filter__theme select').element as HTMLSelectElement).value).toBe('3')
    const chip = wrapper.findAll('.chip').find((item) => item.text().includes('主题：安全'))
    expect(chip).toBeTruthy()
    await chip!.trigger('click')
    await waitDebounce()
    expect(lastListCall().theme).toBeUndefined()
  })

  it('「未挂主题」用 none 哨兵：请求原样带 theme=none', async () => {
    const { wrapper } = await mountAt('/tasks')
    await wrapper.find('.filter__theme select').setValue('none')
    await waitDebounce()
    expect(lastListCall().theme).toBe('none')
  })

  it('ADMIN 行内改主题：PATCH themeId 并局部更新该行，无需整表刷新', async () => {
    const { wrapper } = await mountAt('/tasks')
    const select = wrapper.find('.cell-theme select')
    expect(select.exists()).toBe(true)
    const before = mocks.list.mock.calls.length
    await select.setValue('2')
    await flushPromises()

    expect(mocks.update).toHaveBeenCalledWith(11, { themeId: 2 })
    expect(wrapper.find('.cell-theme').text()).toContain('交付')
    // 只有更新这一条请求，不重新拉列表
    expect(mocks.list.mock.calls.length).toBe(before)
  })

  it('保存失败：回滚旧主题并在单元格下给出错误，不静默吞掉', async () => {
    const { ApiError } = await import('@/api/http')
    mocks.update.mockRejectedValue(
      new ApiError({ status: 422, code: 'THEME_DISABLED', message: '该主题已停用，请重新选择' }),
    )
    const { wrapper } = await mountAt('/tasks')
    await wrapper.find('.cell-theme select').setValue('2')
    await flushPromises()

    expect(wrapper.find('.cell-theme').text()).toContain('安全')
    expect(wrapper.find('.cell-theme__error').text()).toBe('该主题已停用，请重新选择')
  })

  it('非管理员（OWNER）：主题只读标签，不出现行内下拉', async () => {
    const { wrapper } = await mountAt('/tasks', {
      id: 7,
      name: '张三',
      role: 'OWNER',
      mustChangePassword: false,
      landingPath: '/tasks',
    })
    expect(wrapper.find('.cell-theme .theme-tag').exists()).toBe(true)
    expect(wrapper.find('.cell-theme select').exists()).toBe(false)
  })
})
