/** 认证接口（TECHNICAL §5.1） */
import { apiGet, apiPost } from './http'
import type {
  AuthConfig,
  AuthUser,
  AuthorizeUrlResponse,
  ChangePasswordRequest,
  DingTalkAutoRequest,
  LoginRequest,
} from '@/types/api'

export const authApi = {
  /** 同时下发 XSRF-TOKEN Cookie（公开接口） */
  config(): Promise<AuthConfig> {
    return apiGet<AuthConfig>('/auth/config')
  },

  login(payload: LoginRequest): Promise<AuthUser> {
    return apiPost<AuthUser>('/auth/login', payload)
  },

  logout(): Promise<void> {
    return apiPost<void>('/auth/logout')
  },

  me(): Promise<AuthUser> {
    return apiGet<AuthUser>('/auth/me')
  },

  changePassword(payload: ChangePasswordRequest): Promise<void> {
    return apiPost<void>('/auth/password', payload)
  },

  /** 钉钉扫码：拿授权地址后整页跳转（未配置 → 503 DINGTALK_NOT_CONFIGURED） */
  authorizeUrl(next?: string): Promise<AuthorizeUrlResponse> {
    return apiGet<AuthorizeUrlResponse>('/auth/dingtalk/authorize-url', {
      params: next ? { next } : undefined,
    })
  },

  /** 钉钉内免登：jsapi 取 authCode 后换会话 */
  dingtalkAuto(payload: DingTalkAutoRequest): Promise<AuthUser> {
    return apiPost<AuthUser>('/auth/dingtalk/auto', payload)
  },
}
