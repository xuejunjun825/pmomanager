// @vitest-environment jsdom
/**
 * 登录页（DESIGN §4.1 / AC-21）
 *
 * 钉钉入口的显隐只由 /api/auth/config 决定：已配置才出现，未配置整块不渲染，
 * 密码登录任何时候都在。失败文案统一，不泄露账号是否存在。
 */
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { createMemoryHistory, createRouter } from 'vue-router'
import { ApiError } from '@/api/http'
import type { AuthConfig } from '@/types/api'

const api = vi.hoisted(() => ({ config: vi.fn(), me: vi.fn(), login: vi.fn(), authorizeUrl: vi.fn() }))

vi.mock('@/api/auth', () => ({
  authApi: {
    config: api.config,
    me: api.me,
    login: api.login,
    authorizeUrl: api.authorizeUrl,
    logout: vi.fn(),
    changePassword: vi.fn(),
    dingtalkAuto: vi.fn(),
  },
}))

import LoginPage from '@/pages/LoginPage.vue'

const CONFIG: AuthConfig = {
  dingtalkConfigured: false,
  missingKeys: ['DINGTALK_CLIENT_ID', 'DINGTALK_CLIENT_SECRET'],
  passwordLogin: true,
  siteName: '督办系统',
}

async function mountLogin(config: Partial<AuthConfig> = {}) {
  setActivePinia(createPinia())
  api.config.mockResolvedValue({ ...CONFIG, ...config })
  api.me.mockRejectedValue(new ApiError({ status: 401, code: 'AUTH_REQUIRED', message: '未登录' }))

  const router = createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/login', component: LoginPage },
      { path: '/tasks', component: { template: '<div />' } },
    ],
  })
  await router.push('/login')
  await router.isReady()

  const wrapper = mount(LoginPage, { global: { plugins: [router] } })
  await flushPromises()
  return wrapper
}

beforeEach(() => {
  vi.clearAllMocks()
})

describe('钉钉入口显隐', () => {
  it('未配置时整块不渲染，并给出中性说明', async () => {
    const wrapper = await mountLogin({ dingtalkConfigured: false })
    expect(wrapper.text()).not.toContain('钉钉扫码登录')
    expect(wrapper.text()).toContain('使用账号密码登录')
    expect(wrapper.text()).toContain('钉钉未配置，如需开通请联系管理员')
    expect(wrapper.text()).toContain('登录')
  })

  it('已配置时展示钉钉主按钮与「或」分割线，密码表单仍在', async () => {
    const wrapper = await mountLogin({ dingtalkConfigured: true })
    expect(wrapper.text()).toContain('钉钉扫码登录')
    expect(wrapper.text()).toContain('或')
    expect(wrapper.text()).toContain('使用工作账号登录')
    expect(wrapper.findAll('input')).toHaveLength(2)
  })
})

describe('交互', () => {
  it('密码显隐按钮切换 aria-label 与输入类型', async () => {
    const wrapper = await mountLogin()
    const password = wrapper.findAll('input')[1]
    expect(password.attributes('type')).toBe('password')

    const toggle = wrapper.get('button[aria-label="显示密码"]')
    await toggle.trigger('click')
    expect(wrapper.get('button[aria-label="隐藏密码"]')).toBeTruthy()
    expect(wrapper.findAll('input')[1].attributes('type')).toBe('text')
  })

  it('空表单不发请求，直接给出提示', async () => {
    const wrapper = await mountLogin()
    await wrapper.get('form').trigger('submit')
    await flushPromises()
    expect(api.login).not.toHaveBeenCalled()
    expect(wrapper.text()).toContain('请输入账号和密码')
  })

  it('凭证错误统一显示「账号或密码不正确」', async () => {
    api.login.mockRejectedValue(
      new ApiError({ status: 401, code: 'AUTH_BAD_CREDENTIALS', message: '账号或密码不正确' }),
    )
    const wrapper = await mountLogin()
    const inputs = wrapper.findAll('input')
    await inputs[0].setValue('zhang')
    await inputs[1].setValue('wrong-pass')
    await wrapper.get('form').trigger('submit')
    await flushPromises()

    expect(api.login).toHaveBeenCalledWith({ username: 'zhang', password: 'wrong-pass' })
    expect(wrapper.text()).toContain('账号或密码不正确')
    expect(wrapper.get('[role="alert"]').text()).toContain('账号或密码不正确')
  })
})
