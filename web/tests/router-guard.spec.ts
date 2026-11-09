// @vitest-environment jsdom
/**
 * 路由守卫（DESIGN §2.2 / TECHNICAL §6.2）
 *
 * 用内存路由复用生产的那份 routes + registerGuards：测的就是浏览器里跑的那套规则。
 * 身份来源被 mock 在 /api/auth 层，store 的 ensureLoaded 逻辑保持真实。
 */
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { createMemoryHistory, createRouter } from 'vue-router'
import type { AuthUser } from '@/types/api'

const session = vi.hoisted(() => ({ user: null as AuthUser | null }))

vi.mock('@/api/auth', () => ({
  authApi: {
    config: vi.fn(async () => ({
      dingtalkConfigured: false,
      missingKeys: ['DINGTALK_CLIENT_ID'],
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

import { registerGuards, routes } from '@/router'

const ADMIN: AuthUser = { id: 1, name: '张管理', role: 'ADMIN', mustChangePassword: false, landingPath: '/tasks' }
const OWNER: AuthUser = { id: 2, name: '李负责', role: 'OWNER', mustChangePassword: false, landingPath: '/my' }
const EXEC: AuthUser = { id: 3, name: '王总', role: 'EXEC', mustChangePassword: false, landingPath: '/dashboard' }

function freshRouter() {
  setActivePinia(createPinia())
  const router = createRouter({ history: createMemoryHistory(), routes })
  registerGuards(router)
  return router
}

beforeEach(() => {
  session.user = null
})

describe('未登录', () => {
  it('受保护页面一律回登录页，并带上 next', async () => {
    const router = freshRouter()
    await router.push('/tasks?page=2')
    expect(router.currentRoute.value.path).toBe('/login')
    expect(router.currentRoute.value.query.next).toBe('/tasks?page=2')
  })

  it('根路径回登录页且不带 next（避免登录后原地转圈）', async () => {
    const router = freshRouter()
    await router.push('/')
    expect(router.currentRoute.value.path).toBe('/login')
    expect(router.currentRoute.value.query.next).toBeUndefined()
  })

  it('异常页与钉钉回调保持公开', async () => {
    const router = freshRouter()
    await router.push('/403')
    expect(router.currentRoute.value.path).toBe('/403')
    await router.push('/sso/callback')
    expect(router.currentRoute.value.path).toBe('/sso/callback')
  })
})

describe('角色矩阵', () => {
  it('负责人被挡在任务池之外，落到 403', async () => {
    session.user = OWNER
    const router = freshRouter()
    await router.push('/tasks')
    expect(router.currentRoute.value.path).toBe('/403')
  })

  it('管理层看不到用户与设置', async () => {
    session.user = EXEC
    const router = freshRouter()
    await router.push('/users')
    expect(router.currentRoute.value.path).toBe('/403')
    await router.push('/settings')
    expect(router.currentRoute.value.path).toBe('/403')
  })

  it('负责人可以进我的任务，管理层可以进看板与矩阵', async () => {
    session.user = OWNER
    let router = freshRouter()
    await router.push('/my')
    expect(router.currentRoute.value.path).toBe('/my')

    session.user = EXEC
    router = freshRouter()
    await router.push('/dashboard')
    expect(router.currentRoute.value.path).toBe('/dashboard')
    await router.push('/matrix')
    expect(router.currentRoute.value.path).toBe('/matrix')
  })

  it('任务详情对三种角色都放行（他人任务由服务端 404 决定）', async () => {
    session.user = OWNER
    const router = freshRouter()
    await router.push('/tasks/42')
    expect(router.currentRoute.value.name).toBe('task-detail')
    expect(router.currentRoute.value.params.id).toBe('42')
  })
})

describe('落地与回跳', () => {
  it('根路径按角色落地', async () => {
    session.user = OWNER
    const router = freshRouter()
    await router.push('/')
    expect(router.currentRoute.value.path).toBe('/my')
  })

  it('已登录再访问登录页，按 next 回跳', async () => {
    session.user = ADMIN
    const router = freshRouter()
    await router.push('/login?next=%2Fmeetings')
    expect(router.currentRoute.value.path).toBe('/meetings')
  })

  it('已登录访问登录页且 next 是外站地址时，回到角色落地页', async () => {
    session.user = ADMIN
    const router = freshRouter()
    await router.push('/login?next=https%3A%2F%2Fevil.example.com')
    expect(router.currentRoute.value.path).toBe('/tasks')
  })

  it('必须改密不触发跳转：留在原页，由 AppShell 弹模态', async () => {
    session.user = { ...ADMIN, mustChangePassword: true }
    const router = freshRouter()
    await router.push('/tasks')
    expect(router.currentRoute.value.path).toBe('/tasks')
  })
})

describe('未匹配路径', () => {
  it('未知路径落到 404 路由而不是白屏', async () => {
    session.user = ADMIN
    const router = freshRouter()
    await router.push('/no-such-page')
    expect(router.currentRoute.value.name).toBe('not-found')
  })
})
