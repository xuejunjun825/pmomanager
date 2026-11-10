/**
 * 首屏并发形状（TECHNICAL §5 / AC-3 的前端侧防回归）
 *
 * 会话轮换竞态的正确修复在后端（只在登录时换发一次）；本 spec 钉住的是前端**不许**
 * 用「串行化 / 失败重试 / 合并成一次请求」把同类缺陷藏起来（PRD 反面目标 1）——
 * 那会让首屏在服务端换发时继续被踢回登录页。
 *
 * 用两个未决 Promise 断言并发形状：任一 resolve 之前 config 与 me 都已经发出；
 * 再断言 me 401 是正常「未登录」，不抛错且 loaded 置真（守卫据此停止等待）。
 */
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import type { AuthConfig, AuthUser } from '@/types/api'

const mocks = vi.hoisted(() => ({
  config: vi.fn<() => Promise<AuthConfig>>(),
  me: vi.fn<() => Promise<AuthUser>>(),
}))

vi.mock('@/api/auth', () => ({
  authApi: {
    config: mocks.config,
    me: mocks.me,
    login: vi.fn(),
    logout: vi.fn(),
    changePassword: vi.fn(),
    authorizeUrl: vi.fn(),
    dingtalkAuto: vi.fn(),
  },
}))

import { useAuthStore } from '@/stores/auth'

const CONFIG: AuthConfig = {
  dingtalkConfigured: false,
  missingKeys: ['DINGTALK_CLIENT_ID'],
  passwordLogin: true,
  siteName: '督办系统',
}

const ADMIN: AuthUser = { id: 1, name: '张管理', role: 'ADMIN', mustChangePassword: false, landingPath: '/tasks' }

interface Deferred<T> {
  promise: Promise<T>
  resolve: (value: T) => void
  reject: (reason?: unknown) => void
}

function deferred<T>(): Deferred<T> {
  let resolve!: (value: T) => void
  let reject!: (reason?: unknown) => void
  const promise = new Promise<T>((res, rej) => {
    resolve = res
    reject = rej
  })
  return { promise, resolve, reject }
}

beforeEach(() => {
  vi.resetAllMocks()
  setActivePinia(createPinia())
})

describe('auth store ensureLoaded 并发', () => {
  it('config 与 me 在任一 resolve 前已同时发起', async () => {
    const configDeferred = deferred<AuthConfig>()
    const meDeferred = deferred<AuthUser>()
    mocks.config.mockReturnValue(configDeferred.promise)
    mocks.me.mockReturnValue(meDeferred.promise)

    const store = useAuthStore()
    const pending = store.ensureLoaded()

    // 两个请求都还挂着：串行实现此刻 me 还没被调用，合并/去重实现会少一次调用
    expect(mocks.config).toHaveBeenCalledTimes(1)
    expect(mocks.me).toHaveBeenCalledTimes(1)
    expect(store.loading).toBe(true)
    expect(store.loaded).toBe(false)

    configDeferred.resolve(CONFIG)
    meDeferred.resolve(ADMIN)
    await pending

    expect(store.loaded).toBe(true)
    expect(store.loading).toBe(false)
    expect(store.user).toEqual(ADMIN)
    expect(store.siteName).toBe('督办系统')
  })

  it('me 拒绝（401 未登录）时不抛错：loaded 置真、user 为空、config 仍生效', async () => {
    const configDeferred = deferred<AuthConfig>()
    const meDeferred = deferred<AuthUser>()
    mocks.config.mockReturnValue(configDeferred.promise)
    mocks.me.mockReturnValue(meDeferred.promise)

    const store = useAuthStore()
    const pending = store.ensureLoaded()

    meDeferred.reject(new Error('401 AUTH_REQUIRED'))
    configDeferred.resolve(CONFIG)

    // 401 是正常「未登录」，不能抛给调用方（否则每个公开页面都要 try/catch）
    await expect(pending).resolves.toBeUndefined()
    expect(store.user).toBeNull()
    expect(store.isAuthenticated).toBe(false)
    expect(store.loaded).toBe(true)
    expect(store.loading).toBe(false)
    expect(store.siteName).toBe('督办系统')
  })

  it('失败后不自动重试：只有 force 才重新发起请求', async () => {
    mocks.config.mockResolvedValue(CONFIG)
    mocks.me.mockRejectedValue(new Error('401 AUTH_REQUIRED'))

    const store = useAuthStore()
    await store.ensureLoaded()
    expect(store.loaded).toBe(true)

    // 普通再次调用沿用已加载状态；此处若出现第二轮请求，说明被改成了「失败重试」兜底
    await store.ensureLoaded()
    expect(mocks.config).toHaveBeenCalledTimes(1)
    expect(mocks.me).toHaveBeenCalledTimes(1)

    await store.ensureLoaded(true)
    expect(mocks.config).toHaveBeenCalledTimes(2)
    expect(mocks.me).toHaveBeenCalledTimes(2)
  })
})
