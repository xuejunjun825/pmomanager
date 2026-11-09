// @vitest-environment jsdom
/**
 * AppShell 冒烟（DESIGN §3）
 *
 * 走真实主路径：已登录 → 渲染壳层 → 侧栏/顶栏可见 → 首登强制改密模态不可关。
 * 这里不 mock 组件，只有网络层被替身接管，确保壳层与 store 的接缝是通的。
 */
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { createMemoryHistory, createRouter } from 'vue-router'
import type { AuthUser } from '@/types/api'

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

vi.mock('@/api/http', async (importOriginal) => {
  const actual = await importOriginal<typeof import('@/api/http')>()
  return {
    ...actual,
    apiGet: vi.fn(async () => ({ count: 0 })),
    apiPost: vi.fn(async () => ({})),
  }
})

import App from '@/App.vue'
import { registerGuards, routes } from '@/router'

const ADMIN: AuthUser = {
  id: 1,
  name: '张管理',
  role: 'ADMIN',
  mustChangePassword: false,
  landingPath: '/tasks',
}

async function mountApp(user: AuthUser) {
  session.user = user
  setActivePinia(createPinia())
  const router = createRouter({ history: createMemoryHistory(), routes })
  registerGuards(router)
  await router.push('/tasks')
  await router.isReady()
  const wrapper = mount(App, { global: { plugins: [router] }, attachTo: document.body })
  await flushPromises()
  return { wrapper, router }
}

beforeEach(() => {
  session.user = null
  // jsdom 没有 matchMedia，壳层的窄屏折叠逻辑需要它
  vi.stubGlobal('matchMedia', (query: string) => ({
    matches: false,
    media: query,
    addEventListener: () => {},
    removeEventListener: () => {},
    addListener: () => {},
    removeListener: () => {},
    onchange: null,
    dispatchEvent: () => false,
  }))
})

describe('壳层', () => {
  it('已登录时渲染侧栏分组、当前用户与内容区', async () => {
    const { wrapper } = await mountApp(ADMIN)
    const text = wrapper.text()
    expect(text).toContain('督办系统')
    expect(text).toContain('任务池')
    expect(text).toContain('闭环矩阵')
    expect(text).toContain('张管理')
    expect(wrapper.find('main').exists()).toBe(true)
    wrapper.unmount()
  })

  it('首登强制改密弹出不可关闭的模态', async () => {
    const { wrapper } = await mountApp({ ...ADMIN, mustChangePassword: true })
    // 模态 Teleport 到 body，需在文档上找，而不是组件树
    const dialog = document.body.querySelector('[role="dialog"]')
    expect(dialog).not.toBeNull()
    expect(dialog?.textContent).toContain('请先修改初始密码')
    expect(dialog?.textContent).toContain('当前密码')
    expect(dialog?.textContent).toContain('确认新密码')
    // forced：没有关闭图标，也没有「取消」
    expect(dialog?.querySelector('[aria-label="关闭"]')).toBeNull()
    expect(dialog?.textContent).not.toContain('取消')
    wrapper.unmount()
  })
})
