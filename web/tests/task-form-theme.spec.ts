// @vitest-environment jsdom
/**
 * 任务表单的主题与计划开始（r7 T6 / DESIGN §3.7、TECHNICAL §4.5）。
 *
 * 三入口共用同一张表单：新建/编辑都带 `themeId`（none→0 表示清空）与 `plannedStartDate`
 * （留空用 `clearPlannedStart`，日期控件没有 0 语义）；服务端 422 直接回填到这两个字段。
 */
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import { createPinia } from 'pinia'
import type { TaskDetail, ThemeOptionRow } from '@/types/api'

const api = vi.hoisted(() => ({ update: vi.fn(), create: vi.fn(), decisionItems: vi.fn() }))

vi.mock('@/api/tasks', async (importOriginal) => {
  const actual = await importOriginal<typeof import('@/api/tasks')>()
  return {
    ...actual,
    taskApi: { ...actual.taskApi, update: api.update, create: api.create, decisionItems: api.decisionItems },
  }
})

const TaskFormDrawer = (await import('@/components/task/TaskFormDrawer.vue')).default

const THEMES: ThemeOptionRow[] = [
  { id: 1, name: '安全', color: '#175cd3', softColor: '#eff8ff', borderColor: '#b2ddff', sort: 1, enabled: true },
  { id: 2, name: '交付', color: '#067647', softColor: '#ecfdf3', borderColor: '#abefc6', sort: 2, enabled: true },
  { id: 3, name: '停用项', color: '#b42318', softColor: '#fef3f2', borderColor: '#fecdca', sort: 3, enabled: false },
]

function task(overrides: Partial<TaskDetail> = {}): TaskDetail {
  return {
    id: 5,
    title: '补齐上线检查清单',
    owner: { id: 7, name: '张三' },
    dueDate: '2026-12-12',
    source: { type: 'MEETING', meetingId: 2, meetingTitle: '项目周会', decisionItemId: 9 },
    theme: { id: 2, name: '交付', color: '#067647', softColor: '#ecfdf3', borderColor: '#abefc6', enabled: true },
    plannedStartDate: '2026-12-01',
    status: 'PENDING',
    isOverdue: false,
    overdueDays: 0,
    dueSoon: false,
    urgeCount: 0,
    updatedAt: '2026-11-09T09:00:00+08:00',
    dispatcher: { id: 1, name: '李四' },
    priority: 'MEDIUM',
    events: [],
    permissions: {
      canStart: true,
      canSubmit: false,
      canAccept: false,
      canReject: false,
      canCancel: true,
      canRestore: false,
      canForceComplete: true,
      canReassign: true,
      canReschedule: true,
      canUrge: true,
      disabledReasons: {},
    },
    ...overrides,
  }
}

function mountDrawer(current: TaskDetail = task()) {
  return mount(TaskFormDrawer, {
    props: {
      open: true,
      task: current,
      owners: [
        { id: 7, name: '张三', role: 'OWNER', status: 'ACTIVE' },
        { id: 8, name: '赵六', role: 'OWNER', status: 'ACTIVE' },
      ],
      meetings: [{ id: 2, title: '项目周会' }],
      themes: THEMES,
    },
    attachTo: document.body,
    global: { plugins: [createPinia()] },
  })
}

/** Drawer 走 Teleport，表单要落到 document 上查 */
function themeSelect(): HTMLSelectElement {
  const select = document.body.querySelector<HTMLSelectElement>('.task-form__pair select')
  if (!select) throw new Error('主题下拉未渲染')
  return select
}

function startDate(): HTMLInputElement {
  const input = document.body.querySelector<HTMLInputElement>('.task-form__pair input[type="date"]')
  if (!input) throw new Error('计划开始未渲染')
  return input
}

async function selectTheme(value: string) {
  const select = themeSelect()
  select.value = value
  select.dispatchEvent(new Event('change'))
  await flushPromises()
}

async function clearStartDate() {
  const input = startDate()
  input.value = ''
  input.dispatchEvent(new Event('input'))
  await flushPromises()
}

async function submitSave() {
  const save = Array.from(document.body.querySelectorAll('button')).find(
    (item) => item.textContent?.trim() === '保存',
  )
  if (!save) throw new Error('保存按钮未渲染')
  save.click()
  await flushPromises()
}

describe('TaskFormDrawer 主题与计划开始', () => {
  beforeEach(() => {
    api.update.mockReset()
    api.create.mockReset()
    api.decisionItems.mockResolvedValue([])
    api.update.mockResolvedValue(task())
  })

  it('下拉含「未挂主题」与停用主题（带后缀、不可选），回显当前主题', async () => {
    const wrapper = mountDrawer()
    await flushPromises()
    const select = themeSelect()
    expect(select.value).toBe('2')
    const labels = Array.from(select.options).map((item) => item.textContent?.trim())
    expect(labels).toContain('未挂主题')
    expect(labels).toContain('停用项（已停用）')
    const disabled = Array.from(select.options).find((item) => item.textContent?.includes('停用项'))
    expect(disabled?.disabled).toBe(true)
    expect(startDate().value).toBe('2026-12-01')
    wrapper.unmount()
  })

  it('改主题 + 保留计划开始：提交 themeId 与 plannedStartDate', async () => {
    const wrapper = mountDrawer()
    await flushPromises()
    await selectTheme('1')
    await submitSave()

    expect(api.update).toHaveBeenCalledWith(
      5,
      expect.objectContaining({ themeId: 1, plannedStartDate: '2026-12-01' }),
    )
    expect(wrapper.emitted('saved')).toHaveLength(1)
    wrapper.unmount()
  })

  it('清空主题与计划开始：themeId=0 + clearPlannedStart=true（日期无「0」语义）', async () => {
    const wrapper = mountDrawer()
    await flushPromises()
    await selectTheme('none')
    await clearStartDate()
    await submitSave()

    const body = api.update.mock.calls[0][1] as Record<string, unknown>
    expect(body).toMatchObject({ themeId: 0, clearPlannedStart: true })
    expect(body).not.toHaveProperty('plannedStartDate')
    wrapper.unmount()
  })

  it('422 字段错误回填到主题字段：文案在行内，弹层不关闭', async () => {
    const { ApiError } = await import('@/api/http')
    api.update.mockRejectedValue(
      new ApiError({
        status: 422,
        code: 'VALIDATION_FAILED',
        message: '还有 1 项需要修改',
        fieldErrors: [{ field: 'themeId', message: '该主题不存在或已停用，请重新选择' }],
      }),
    )
    const wrapper = mountDrawer()
    await flushPromises()
    await submitSave()

    expect(document.body.textContent).toContain('该主题不存在或已停用，请重新选择')
    expect(document.body.textContent).toContain('还有 1 项需要修改')
    expect(wrapper.emitted('close')).toBeUndefined()
    wrapper.unmount()
  })
})
