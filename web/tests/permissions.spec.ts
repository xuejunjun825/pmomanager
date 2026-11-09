/**
 * 访问矩阵与 next 参数（DESIGN §2.2 / TECHNICAL §6.2）
 * 这些规则同时决定菜单可见性与路由守卫，纯函数才能被逐条钉住。
 */
import { describe, expect, it } from 'vitest'
import {
  canSeeNav,
  canSeePage,
  isNavActive,
  landingPathFor,
  navGroupsFor,
  roleLabel,
  safeInternalPath,
} from '@/utils/permissions'

describe('landingPathFor', () => {
  it('按角色给出落地页', () => {
    expect(landingPathFor('ADMIN')).toBe('/tasks')
    expect(landingPathFor('OWNER')).toBe('/my')
    expect(landingPathFor('EXEC')).toBe('/dashboard')
  })
})

describe('canSeePage', () => {
  it('负责人不进任务池、矩阵与看板', () => {
    expect(canSeePage('OWNER', '/tasks')).toBe(false)
    expect(canSeePage('OWNER', '/matrix')).toBe(false)
    expect(canSeePage('OWNER', '/dashboard')).toBe(false)
    expect(canSeePage('OWNER', '/my')).toBe(true)
  })

  it('任务详情对三种角色都放行：是否本人由服务端 404 决定', () => {
    expect(canSeePage('OWNER', '/tasks/42')).toBe(true)
    expect(canSeePage('EXEC', '/tasks/42')).toBe(true)
    expect(canSeePage('ADMIN', '/tasks/42')).toBe(true)
  })

  it('会议、用户与设置只对管理员开放', () => {
    expect(canSeePage('OWNER', '/meetings')).toBe(false)
    expect(canSeePage('EXEC', '/meetings/7')).toBe(false)
    expect(canSeePage('ADMIN', '/meetings/7')).toBe(true)
    expect(canSeePage('EXEC', '/users')).toBe(false)
    expect(canSeePage('OWNER', '/settings')).toBe(false)
    expect(canSeePage('ADMIN', '/settings')).toBe(true)
  })

  it('忽略 query 与 hash；未列入矩阵的路径默认放行', () => {
    expect(canSeePage('OWNER', '/tasks?page=2')).toBe(false)
    expect(canSeePage('ADMIN', '/notifications')).toBe(true)
    expect(canSeePage('EXEC', '/sso/callback')).toBe(true)
  })
})

describe('菜单', () => {
  it('负责人只看到「我的任务」与通知之外的首页组', () => {
    const keys = navGroupsFor('OWNER').flatMap((group) => group.items.map((item) => item.key))
    expect(keys).toEqual(['my'])
  })

  it('管理层看到工作台与任务池，不看到系统组', () => {
    const keys = navGroupsFor('EXEC').flatMap((group) => group.items.map((item) => item.key))
    expect(keys).toContain('dashboard')
    expect(keys).toContain('matrix')
    expect(keys).toContain('tasks')
    expect(keys).not.toContain('users')
    expect(keys).not.toContain('settings')
  })

  it('管理员看到系统组；未登录时没有任何组', () => {
    const adminKeys = navGroupsFor('ADMIN').flatMap((group) => group.items.map((item) => item.key))
    expect(adminKeys).toContain('users')
    expect(navGroupsFor(null)).toEqual([])
  })

  it('canSeeNav 对未知 key 返回 false；isNavActive 支持子路径', () => {
    expect(canSeeNav('ADMIN', 'dashboard')).toBe(true)
    expect(canSeeNav('OWNER', 'dashboard')).toBe(false)
    expect(canSeeNav('ADMIN', 'nope')).toBe(false)
    expect(isNavActive('/tasks', '/tasks/42')).toBe(true)
    expect(isNavActive('/tasks', '/tasks-archive')).toBe(false)
  })

  it('角色展示词固定', () => {
    expect(roleLabel('ADMIN')).toBe('管理员')
    expect(roleLabel('OWNER')).toBe('负责人')
    expect(roleLabel('EXEC')).toBe('管理层')
    expect(roleLabel(null)).toBe('')
  })
})

describe('safeInternalPath（防开放重定向）', () => {
  it('保留站内相对路径', () => {
    expect(safeInternalPath('/tasks?page=2')).toBe('/tasks?page=2')
    expect(safeInternalPath('  /matrix  ')).toBe('/matrix')
  })

  it('拒绝外部地址与协议相对地址', () => {
    expect(safeInternalPath('https://evil.example.com')).toBeNull()
    expect(safeInternalPath('//evil.example.com')).toBeNull()
    expect(safeInternalPath('/\\evil.example.com')).toBeNull()
    expect(safeInternalPath('javascript:alert(1)')).toBeNull()
    expect(safeInternalPath('tasks')).toBeNull()
  })

  it('拒绝回到登录页本身与非字符串', () => {
    expect(safeInternalPath('/login')).toBeNull()
    expect(safeInternalPath(undefined)).toBeNull()
    expect(safeInternalPath(['/tasks'])).toBeNull()
  })
})
