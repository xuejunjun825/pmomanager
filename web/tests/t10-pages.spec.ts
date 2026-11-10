// @vitest-environment jsdom
/**
 * T10 页面级验证（通知 / 用户 / 设置 + 两个通用组件）。
 *
 * 只替身网络层（api 模块），页面、store、路由、组件保持真实：
 * 目的是钉住「点击通知→置已读→跳任务」「全部已读刷新角标」「改角色/停用/重置」
 * 「收件人为空提示」「时间非法不可保存」这些跨组件的接缝，而不是测样式。
 */
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import { createPinia } from 'pinia'
import { createMemoryHistory, createRouter } from 'vue-router'
import type { Component } from 'vue'
import type { NotificationRow, SettingsView, UserRow } from '@/types/api'

const notificationsApiMock = vi.hoisted(() => ({
  list: vi.fn(),
  unreadCount: vi.fn(),
  markRead: vi.fn(),
  markAllRead: vi.fn(),
}))
const usersApiMock = vi.hoisted(() => ({
  page: vi.fn(),
  options: vi.fn(),
  updateRole: vi.fn(),
  updateStatus: vi.fn(),
  resetPassword: vi.fn(),
}))
const settingsApiMock = vi.hoisted(() => ({ get: vi.fn(), updateReport: vi.fn() }))
// r4：设置页新增「项目配置」卡（仅 ADMIN 在 onMounted 拉取），不替身会打真实 XHR。
const projectsApiMock = vi.hoisted(() => ({
  list: vi.fn(),
  create: vi.fn(),
  update: vi.fn(),
  setEnabled: vi.fn(),
  remove: vi.fn(),
}))
// r3 起设置页的「来源配置」卡同样在 onMounted 拉取（list + palette），一并未替身。
const sourceTypesApiMock = vi.hoisted(() => ({
  list: vi.fn(),
  palette: vi.fn(),
  create: vi.fn(),
  update: vi.fn(),
  setEnabled: vi.fn(),
  remove: vi.fn(),
}))

vi.mock('@/api/notifications', () => ({ notificationsApi: notificationsApiMock }))
vi.mock('@/api/users', () => ({ usersApi: usersApiMock }))
vi.mock('@/api/settings', () => ({ settingsApi: settingsApiMock }))
vi.mock('@/api/projects', () => ({ projectsApi: projectsApiMock }))
vi.mock('@/api/sourceTypes', () => ({ sourceTypesApi: sourceTypesApiMock }))

import ChannelChip from '@/components/ui/ChannelChip.vue'
import ConfirmDialog from '@/components/ui/ConfirmDialog.vue'
import NotificationsPage from '@/pages/NotificationsPage.vue'
import UsersPage from '@/pages/UsersPage.vue'
import SettingsPage from '@/pages/SettingsPage.vue'
import { useNotificationsStore } from '@/stores/notifications'

const NOW = '2026-11-09T09:30:00+08:00'

function notification(over: Partial<NotificationRow> = {}): NotificationRow {
  return {
    id: 1,
    type: 'TASK_OVERDUE',
    typeLabel: '任务逾期',
    taskId: 7,
    taskTitle: '季度复盘材料',
    ownerName: '李负责',
    createdAt: NOW,
    channelStatus: 'SENT',
    channelLabel: '钉钉已送达',
    read: false,
    ...over,
  } as NotificationRow
}

function user(over: Partial<UserRow> = {}): UserRow {
  return {
    id: 2,
    username: 'lifuze',
    name: '李负责',
    role: 'OWNER',
    status: 'ACTIVE',
    dingtalkBound: true,
    mustChangePassword: false,
    createdAt: NOW,
    ...over,
  }
}

function settingsView(over: Partial<SettingsView> = {}): SettingsView {
  return {
    report: {
      dailyEnabled: true,
      dailyTime: '18:00',
      weeklyEnabled: true,
      weeklyTime: '09:00',
      recipients: [],
      recipientsEmpty: true,
    },
    dingtalk: {
      configured: false,
      missingKeys: ['DINGTALK_CORP_ID', 'DINGTALK_APP_SECRET', 'DINGTALK_CALLBACK_DOMAIN'],
    },
    updatedAt: NOW,
    ...over,
  } as SettingsView
}

async function mountAt(component: Component, path: string) {
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [
      { path, component },
      { path: '/tasks/:id', component: { template: '<div class="task-stub">任务详情</div>' } },
      { path: '/elsewhere', component: { template: '<div>别处</div>' } },
    ],
  })
  await router.push(path)
  await router.isReady()
  const pinia = createPinia()
  const wrapper = mount({ template: '<router-view />' }, {
    global: { plugins: [router, pinia], stubs: { teleport: true } },
  })
  await flushPromises()
  return { wrapper, router, pinia }
}

function buttonByText(wrapper: ReturnType<typeof mount>, text: string) {
  return wrapper.findAll('button').filter((btn) => btn.text() === text)
}

beforeEach(() => {
  vi.clearAllMocks()
  notificationsApiMock.markRead.mockResolvedValue(undefined)
  notificationsApiMock.markAllRead.mockResolvedValue({ updated: 1 })
  usersApiMock.page.mockResolvedValue({ items: [user()], total: 1, page: 1, size: 20 })
  usersApiMock.options.mockResolvedValue([])
  settingsApiMock.get.mockResolvedValue(settingsView())
  settingsApiMock.updateReport.mockResolvedValue(undefined)
  projectsApiMock.list.mockResolvedValue([])
  sourceTypesApiMock.list.mockResolvedValue([])
  sourceTypesApiMock.palette.mockResolvedValue([])
})

describe('ChannelChip', () => {
  it('五态文案固定，未配置与未绑定都是中性灰', () => {
    const cases = [
      ['SENT', '钉钉已送达', 'chip--success'],
      ['FAILED', '钉钉失败', 'chip--danger'],
      ['NOT_CONFIGURED', '钉钉未配置', 'chip--neutral'],
      ['UNBOUND', '未绑定钉钉', 'chip--neutral'],
      ['SKIPPED', '站内', 'chip--neutral'],
    ] as const
    for (const [status, label, tone] of cases) {
      const chip = mount(ChannelChip, { props: { status } })
      expect(chip.text()).toBe(label)
      expect(chip.classes()).toContain(tone)
      if (tone === 'chip--neutral') expect(chip.classes()).not.toContain('chip--danger')
      expect(chip.element.tagName).toBe('SPAN')
    }
  })

  it('只有「钉钉失败 + interactive」可点，并抛出 select', async () => {
    const failed = mount(ChannelChip, { props: { status: 'FAILED', interactive: true } })
    expect(failed.element.tagName).toBe('BUTTON')
    await failed.trigger('click')
    expect(failed.emitted('select')?.[0]).toEqual(['FAILED'])

    const sent = mount(ChannelChip, { props: { status: 'SENT', interactive: true } })
    expect(sent.element.tagName).toBe('SPAN')
  })
})

describe('ConfirmDialog', () => {
  it('危险确认渲染实心红类，确认/取消各发一次事件', async () => {
    const dialog = mount(ConfirmDialog, {
      props: { open: true, title: '停用账号', tone: 'danger', confirmLabel: '停用' },
      global: { stubs: { teleport: true } },
    })
    expect(dialog.text()).toContain('停用账号')
    const danger = dialog.find('.confirm__danger')
    expect(danger.exists()).toBe(true)

    await buttonByText(dialog, '停用')[0].trigger('click')
    expect(dialog.emitted('confirm')).toHaveLength(1)
    await buttonByText(dialog, '取消')[0].trigger('click')
    expect(dialog.emitted('close')).toHaveLength(1)
  })
})

describe('NotificationsPage', () => {
  it('倒序列表 + 未读点；点击通知先置已读再跳任务详情', async () => {
    notificationsApiMock.list.mockImplementation((query: { type?: string }) =>
      Promise.resolve(
        query?.type === 'MENTION'
          ? { items: [], total: 4, page: 1, size: 1, unreadCount: 1 }
          : {
              items: [
                notification(),
                notification({
                  id: 2,
                  typeLabel: '督办日报',
                  taskId: null,
                  taskTitle: null,
                  channelStatus: 'NOT_CONFIGURED',
                  channelLabel: '钉钉未配置',
                  read: true,
                }),
              ],
              total: 2,
              page: 1,
              size: 20,
              unreadCount: 1,
            },
      ),
    )
    const { wrapper, router, pinia } = await mountAt(NotificationsPage, '/notifications')
    const store = useNotificationsStore(pinia)

    expect(wrapper.findAll('.notice')).toHaveLength(2)
    expect(wrapper.findAll('.notice--unread')).toHaveLength(1)
    expect(wrapper.text()).toContain('季度复盘材料')
    expect(wrapper.text()).toContain('钉钉未配置')
    expect(store.unreadCount).toBe(1)

    await wrapper.findAll('.notice__hit')[0].trigger('click')
    await flushPromises()

    expect(notificationsApiMock.markRead).toHaveBeenCalledWith(1)
    expect(store.unreadCount).toBe(0)
    expect(router.currentRoute.value.fullPath).toBe('/tasks/7')
  })

  it('切到未读 tab 带 unreadOnly=true 重新查询；空态文案区分全部/未读', async () => {
    notificationsApiMock.list.mockImplementation((query: { unreadOnly?: boolean }) =>
      Promise.resolve(
        query?.unreadOnly
          ? { items: [], total: 0, page: 1, size: 20, unreadCount: 0 }
          : { items: [notification()], total: 1, page: 1, size: 20, unreadCount: 1 },
      ),
    )

    const { wrapper } = await mountAt(NotificationsPage, '/notifications')
    const tabs = wrapper.findAll('.tabs__item')
    await tabs[2].trigger('click')
    await flushPromises()

    expect(notificationsApiMock.list).toHaveBeenLastCalledWith({ unreadOnly: true, page: 1, size: 20 })
    expect(wrapper.text()).toContain('全部已读，干得漂亮')
    expect(wrapper.text()).not.toContain('暂无通知')
  })

  it('全部标为已读：二次确认 → 调接口 → 角标归零', async () => {
    // 标为已读后的重载由服务端如实返回 0
    let cleared = false
    notificationsApiMock.list.mockImplementation((query: { type?: string }) =>
      Promise.resolve(
        query?.type === 'MENTION'
          ? { items: [], total: 1, page: 1, size: 1, unreadCount: cleared ? 0 : 1 }
          : {
              items: [notification({ read: cleared })],
              total: 1,
              page: 1,
              size: 20,
              unreadCount: cleared ? 0 : 1,
            },
      ),
    )
    notificationsApiMock.markAllRead.mockImplementation(async () => {
      cleared = true
      return { updated: 1 }
    })
    const { wrapper, pinia } = await mountAt(NotificationsPage, '/notifications')
    const store = useNotificationsStore(pinia)

    await buttonByText(wrapper, '全部标为已读')[0].trigger('click')
    await flushPromises()
    expect(wrapper.text()).toContain('此操作不可撤销')

    const confirm = buttonByText(wrapper, '全部标为已读')
    await confirm[confirm.length - 1].trigger('click')
    await flushPromises()

    expect(notificationsApiMock.markAllRead).toHaveBeenCalledTimes(1)
    expect(store.unreadCount).toBe(0)
  })
})

describe('UsersPage', () => {
  it('改角色走 PATCH，并就地更新行', async () => {
    usersApiMock.page.mockResolvedValue({ items: [user()], total: 1, page: 1, size: 20 })
    usersApiMock.updateRole.mockResolvedValue(user({ role: 'EXEC' }))
    const { wrapper } = await mountAt(UsersPage, '/users')

    expect(wrapper.text()).toContain('李负责')
    expect(wrapper.text()).toContain('任务负责人')

    await wrapper.find('.role').trigger('click')
    expect(wrapper.text()).toContain('角色变更立即生效')

    await wrapper.find('input[value="EXEC"]').setValue()
    await buttonByText(wrapper, '保存角色')[0].trigger('click')
    await flushPromises()

    expect(usersApiMock.updateRole).toHaveBeenCalledWith(2, 'EXEC')
    expect(wrapper.text()).toContain('管理层')
  })

  it('停用先确认再 PATCH；文案说明名下任务保留', async () => {
    usersApiMock.page.mockResolvedValue({ items: [user()], total: 1, page: 1, size: 20 })
    usersApiMock.updateStatus.mockResolvedValue(user({ status: 'DISABLED' }))
    const { wrapper } = await mountAt(UsersPage, '/users')

    await wrapper.find('.switch').trigger('click')
    await flushPromises()
    expect(wrapper.text()).toContain('名下任务保留')

    const confirm = buttonByText(wrapper, '停用账号')
    await confirm[confirm.length - 1].trigger('click')
    await flushPromises()

    expect(usersApiMock.updateStatus).toHaveBeenCalledWith(2, 'DISABLED')
  })

  it('重置密码：确认后展示一次性临时密码', async () => {
    usersApiMock.page.mockResolvedValue({ items: [user()], total: 1, page: 1, size: 20 })
    usersApiMock.resetPassword.mockResolvedValue({ temporaryPassword: 'Tmp-8842-xz' })
    const { wrapper } = await mountAt(UsersPage, '/users')

    await buttonByText(wrapper, '重置密码')[0].trigger('click')
    await flushPromises()
    await buttonByText(wrapper, '生成临时密码')[0].trigger('click')
    await flushPromises()

    expect(usersApiMock.resetPassword).toHaveBeenCalledWith(2)
    expect(wrapper.text()).toContain('Tmp-8842-xz')
    expect(wrapper.text()).toContain('仅显示一次'.replace('仅显示一次', '关闭后无法再次查看'))
  })
})

describe('SettingsPage', () => {
  it('收件人为空显示黄条；加入收件人后出现保存条并按 payload 保存', async () => {
    usersApiMock.options.mockResolvedValue([
      { id: 9, name: '王领导', role: 'EXEC', status: 'ACTIVE' },
      { id: 3, name: '李负责', role: 'OWNER', status: 'ACTIVE' },
    ])
    settingsApiMock.updateReport.mockResolvedValue(undefined)
    const { wrapper } = await mountAt(SettingsPage, '/settings')

    expect(wrapper.text()).toContain('未配置收件人，日报/周报将不会发送')
    expect(wrapper.find('.savebar').exists()).toBe(false)

    await wrapper.findAll('.candidate')[0].trigger('click')
    await flushPromises()
    expect(wrapper.text()).not.toContain('未配置收件人，日报/周报将不会发送')
    expect(wrapper.find('.savebar').exists()).toBe(true)

    await buttonByText(wrapper, '保存设置')[0].trigger('click')
    await flushPromises()

    expect(settingsApiMock.updateReport).toHaveBeenCalledWith({
      dailyEnabled: true,
      dailyTime: '18:00',
      weeklyEnabled: true,
      weeklyTime: '09:00',
      recipientIds: [9],
    })
    expect(settingsApiMock.get).toHaveBeenCalledTimes(2)
  })

  it('钉钉缺失项逐项标缺失，且不出现危险色', async () => {
    const { wrapper } = await mountAt(SettingsPage, '/settings')
    const missing = wrapper.findAll('.checks__item--missing')
    expect(missing.length).toBe(3)
    expect(wrapper.text()).toContain('未配置')
    expect(wrapper.text()).toContain('钉钉推送不会发出')
    // 未配置是部署事实，不是错误：卡片内不应出现 danger 语义
    expect(wrapper.find('.card .checks').html()).not.toContain('danger')
  })

  it('时间非法时给出行内错误并禁用保存', async () => {
    const { wrapper } = await mountAt(SettingsPage, '/settings')
    const timeInputs = wrapper.findAll('input[type="time"]')
    await timeInputs[0].setValue('')
    await timeInputs[1].setValue('25:00')

    expect(wrapper.text()).toContain('请填写 24 小时制时间')
    await wrapper.findAll('.candidate')[0]?.trigger('click')
    const save = buttonByText(wrapper, '保存设置')[0]
    expect(save.attributes('disabled')).toBeDefined()
  })
})
