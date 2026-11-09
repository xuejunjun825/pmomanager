/**
 * 当前身份（唯一来源，TECHNICAL §6.2）。组件不得自行保存 user，
 * 否则角色判断会在「刷新后」「改密后」两处分叉。
 */
import { computed, ref } from 'vue'
import { defineStore } from 'pinia'
import { authApi } from '@/api/auth'
import type { AuthConfig, AuthUser } from '@/types/api'
import type { Role } from '@/types/enums'
import { landingPathFor } from '@/utils/permissions'

export const useAuthStore = defineStore('auth', () => {
  const user = ref<AuthUser | null>(null)
  const config = ref<AuthConfig | null>(null)
  /** 至少完成一次 /me 探测（无论成功失败），守卫据此决定是否等待 */
  const loaded = ref(false)
  const loading = ref(false)

  let inflight: Promise<void> | null = null

  const role = computed<Role | null>(() => user.value?.role ?? null)
  const isAuthenticated = computed(() => user.value !== null)
  const mustChangePassword = computed(() => user.value?.mustChangePassword === true)
  const siteName = computed(() => config.value?.siteName || '会议任务督办系统')
  const dingtalkConfigured = computed(() => config.value?.dingtalkConfigured === true)

  /**
   * 首次导航前调用：并发请求 config + me。
   * /me 401 是正常「未登录」，不抛错——否则每个公开页面都要 try/catch 一遍。
   */
  async function ensureLoaded(force = false): Promise<void> {
    if (loaded.value && !force) return
    if (inflight) return inflight
    inflight = (async () => {
      loading.value = true
      try {
        const [configResult, meResult] = await Promise.allSettled([authApi.config(), authApi.me()])
        if (configResult.status === 'fulfilled') config.value = configResult.value
        user.value = meResult.status === 'fulfilled' ? meResult.value : null
        loaded.value = true
      } finally {
        loading.value = false
        inflight = null
      }
    })()
    await inflight
  }

  async function login(username: string, password: string): Promise<AuthUser> {
    const me = await authApi.login({ username, password })
    user.value = me
    loaded.value = true
    return me
  }

  async function logout(): Promise<void> {
    try {
      await authApi.logout()
    } catch {
      // 网络异常也清空本地身份：服务端会话会在过期后自然失效
    } finally {
      clear()
    }
  }

  async function changePassword(oldPassword: string, newPassword: string): Promise<void> {
    await authApi.changePassword({ oldPassword, newPassword })
    if (user.value) user.value = { ...user.value, mustChangePassword: false }
  }

  /** 401 处理器与「退出登录」共用 */
  function clear(): void {
    user.value = null
    loaded.value = true
  }

  /** 服务端明确给出的落地页优先，前端映射兜底 */
  function landingPath(): string {
    return user.value?.landingPath || (role.value ? landingPathFor(role.value) : '/login')
  }

  return {
    user,
    config,
    loaded,
    loading,
    role,
    isAuthenticated,
    mustChangePassword,
    siteName,
    dingtalkConfigured,
    ensureLoaded,
    login,
    logout,
    changePassword,
    clear,
    landingPath,
  }
})
