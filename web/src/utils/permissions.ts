/**
 * 角色 → 页面/菜单可见性（DESIGN §2.1 / §2.2；TECHNICAL §6.1）
 *
 * 只控制「入口是否出现」。真正的安全由服务端按 AC-2 校验：
 * 隐藏菜单不是安全措施，越权接口返回 403/404 由页面落到异常页。
 */
import type { Role } from '@/types/enums'

export type NavIcon = 'dashboard' | 'matrix' | 'tasks' | 'my' | 'meetings' | 'users' | 'settings'

export interface NavItem {
  key: string
  label: string
  path: string
  icon: NavIcon
  roles: readonly Role[]
}

export interface NavGroup {
  key: string
  title: string
  items: NavItem[]
}

export const NAV_GROUPS: readonly NavGroup[] = [
  {
    key: 'workspace',
    title: '工作台',
    items: [
      { key: 'dashboard', label: '督办看板', path: '/dashboard', icon: 'dashboard', roles: ['ADMIN', 'EXEC'] },
      { key: 'matrix', label: '闭环矩阵', path: '/matrix', icon: 'matrix', roles: ['ADMIN', 'EXEC'] },
    ],
  },
  {
    key: 'tasks',
    title: '任务',
    items: [
      { key: 'tasks', label: '任务池', path: '/tasks', icon: 'tasks', roles: ['ADMIN', 'EXEC'] },
      { key: 'my', label: '我的任务', path: '/my', icon: 'my', roles: ['ADMIN', 'OWNER'] },
    ],
  },
  {
    key: 'meetings',
    title: '会议',
    items: [{ key: 'meetings', label: '会议与决议', path: '/meetings', icon: 'meetings', roles: ['ADMIN'] }],
  },
  {
    key: 'system',
    title: '系统',
    items: [
      { key: 'users', label: '用户与角色', path: '/users', icon: 'users', roles: ['ADMIN'] },
      { key: 'settings', label: '系统设置', path: '/settings', icon: 'settings', roles: ['ADMIN'] },
    ],
  },
] as const

/** 落地页：admin → /tasks；owner → /my；exec → /dashboard（DESIGN §2.1） */
const LANDING_BY_ROLE: Record<Role, string> = {
  ADMIN: '/tasks',
  OWNER: '/my',
  EXEC: '/dashboard',
}

export function landingPathFor(role: Role): string {
  return LANDING_BY_ROLE[role] ?? '/login'
}

/**
 * 页面级访问矩阵。`/tasks/:id` 对所有角色可见——是否本人由服务端 404 决定，
 * 前端不能靠角色提前判断，否则会把「他人任务」暴露成 403（泄露存在性）。
 */
const PAGE_ACCESS: ReadonlyArray<{ pattern: RegExp; roles: readonly Role[] }> = [
  { pattern: /^\/tasks\/\d+$/, roles: ['ADMIN', 'OWNER', 'EXEC'] },
  { pattern: /^\/tasks$/, roles: ['ADMIN', 'EXEC'] },
  { pattern: /^\/my$/, roles: ['ADMIN', 'OWNER'] },
  { pattern: /^\/meetings(?:\/\d+)?$/, roles: ['ADMIN'] },
  { pattern: /^\/matrix$/, roles: ['ADMIN', 'EXEC'] },
  { pattern: /^\/dashboard$/, roles: ['ADMIN', 'EXEC'] },
  { pattern: /^\/users$/, roles: ['ADMIN'] },
  { pattern: /^\/settings$/, roles: ['ADMIN'] },
  { pattern: /^\/notifications$/, roles: ['ADMIN', 'OWNER', 'EXEC'] },
]

export function canSeePage(role: Role, path: string): boolean {
  const clean = (path || '').split('?')[0].split('#')[0]
  const rule = PAGE_ACCESS.find((entry) => entry.pattern.test(clean))
  return rule ? rule.roles.includes(role) : true
}

export function canSeeNav(role: Role, key: string): boolean {
  const item = NAV_GROUPS.flatMap((group) => group.items).find((entry) => entry.key === key)
  return item ? item.roles.includes(role) : false
}

/** 侧栏只渲染当前角色有权限的组；空组不占位 */
export function navGroupsFor(role: Role | null): NavGroup[] {
  if (!role) return []
  return NAV_GROUPS.map((group) => ({
    ...group,
    items: group.items.filter((item) => item.roles.includes(role)),
  })).filter((group) => group.items.length > 0)
}

/** 导航选中：详情页（/tasks/12）仍高亮父级菜单 */
export function isNavActive(itemPath: string, currentPath: string): boolean {
  if (currentPath === itemPath) return true
  return currentPath.startsWith(`${itemPath}/`)
}

/**
 * 只接受站内相对路径的 next 参数（防开放重定向）。
 * 拒绝 //evil.com、http://…、javascript: 等形态。
 */
export function safeInternalPath(value: unknown): string | null {
  if (typeof value !== 'string') return null
  const candidate = value.trim()
  if (!candidate.startsWith('/')) return null
  if (candidate.startsWith('//')) return null
  if (/^\/\\/.test(candidate)) return null
  if (candidate === '/login') return null
  return candidate
}

/** 角色标签（侧栏/顶栏用户区共用） */
export function roleLabel(role: Role | null | undefined): string {
  if (role === 'ADMIN') return '管理员'
  if (role === 'OWNER') return '负责人'
  if (role === 'EXEC') return '管理层'
  return ''
}
