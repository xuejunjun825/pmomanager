// @vitest-environment jsdom
/**
 * T08 建任务绑项目（AC-2 / AC-3 / DESIGN §3.3）。
 *
 * 钉住「项目型来源」这条新形态在表单里的四处接缝：
 * 1. 形态是 `form === 'PROJECT'` 时，会议与说明字段消失、出现必选的「来源项目」；
 * 2. 没有项目不能提交（本地拦下，不打服务端），选了之后 payload 只带 `sourceProjectId`；
 * 3. 切回说明型时项目位清空，不会把上一形态的字段带进 payload（三态互斥）；
 * 4. 无可用项目 / 编辑态回显两种边界：下拉禁用不隐藏；只读行显示名称与停用后缀。
 */
import { afterEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import { createPinia } from 'pinia'
import TaskFormDrawer from '@/components/task/TaskFormDrawer.vue'
import { ApiError } from '@/api/http'
import { useUiStore } from '@/stores/ui'
import type { ProjectOptionRow, SourceTypeOption, TaskDetail, UserOption } from '@/types/api'

const taskApiMock = vi.hoisted(() => ({
  decisionItems: vi.fn().mockResolvedValue([]),
  create: vi.fn(),
  update: vi.fn(),
}))

vi.mock('@/api/tasks', () => ({ taskApi: taskApiMock }))

function source(over: Partial<SourceTypeOption>): SourceTypeOption {
  return {
    value: 'MEETING',
    label: '会议决议',
    color: '#2563EB',
    softColor: '#EFF6FF',
    borderColor: '#BFDBFE',
    form: 'MEETING',
    enabled: true,
    sort: 1,
    ...over,
  }
}

const SOURCE_CONFIG: SourceTypeOption[] = [
  source({ value: 'MEETING', label: '会议决议', form: 'MEETING', sort: 1 }),
  source({ value: 'S20001', label: '客户投诉', form: 'PROJECT', sort: 2 }),
  source({ value: 'NOTE_TYPE', label: '临时待办', form: 'NOTE', sort: 3 }),
]

const PROJECTS: ProjectOptionRow[] = [
  { id: 12, name: '老旧小区改造', enabled: true, sort: 1 },
  { id: 9, name: '停用项目', enabled: false, sort: 2 },
]

const OWNERS: UserOption[] = [{ id: 7, name: '张三', role: 'OWNER', status: 'ACTIVE' }]

const mounted: Array<{ unmount: () => void }> = []
afterEach(() => {
  mounted.splice(0).forEach((item) => item.unmount())
  document.body.innerHTML = ''
  vi.clearAllMocks()
})

interface DrawerProps {
  open: boolean
  task?: TaskDetail | null
  owners?: UserOption[]
  meetings?: Array<{ id: number; title: string }>
  sourceTypes?: SourceTypeOption[]
  projects?: ProjectOptionRow[]
}

function mountDrawer(props: DrawerProps) {
  const wrapper = mount(TaskFormDrawer, {
    props: { owners: OWNERS, meetings: [], ...props },
    attachTo: document.body,
    global: { plugins: [createPinia()] },
  })
  mounted.push(wrapper)
  return wrapper
}

/** 抽屉 Teleport 到 body：按字段标签定位，避免依赖 DOM 顺序 */
function fieldByLabel(label: string): HTMLElement | undefined {
  const node = [...document.body.querySelectorAll('.field__label')].find((item) =>
    item.textContent?.trim().startsWith(label),
  )
  return (node?.parentElement ?? undefined) as HTMLElement | undefined
}

function sourceSelect(): HTMLSelectElement | undefined {
  return (fieldByLabel('来源类型')?.querySelector('select') ?? undefined) as
    | HTMLSelectElement
    | undefined
}

async function pickFromSelect(label: string, value: string): Promise<void> {
  const select = fieldByLabel(label)?.querySelector('select') as HTMLSelectElement | undefined
  if (!select) throw new Error(`找不到下拉：${label}`)
  select.value = value
  select.dispatchEvent(new Event('change', { bubbles: true }))
  await flushPromises()
}

/** 真实交互：点开 combobox 再点选项（覆盖组件自己的可访问性路径，不直接 emit） */
async function pickProject(label: string): Promise<void> {
  const field = fieldByLabel('来源项目')
  if (!field) throw new Error('抽屉里没有「来源项目」字段')
  const control = field.querySelector('.field__control') as HTMLElement | null
  if (!control) throw new Error('「来源项目」字段没有可点区域')
  control.dispatchEvent(new MouseEvent('mousedown', { bubbles: true }))
  await flushPromises()
  const option = [...field.querySelectorAll('.list .option')].find((item) =>
    item.textContent?.includes(label),
  )
  if (!option) throw new Error(`项目下拉里找不到：${label}`)
  ;(option as HTMLElement).click()
  await flushPromises()
}

async function fillBasics(): Promise<void> {
  const title = fieldByLabel('任务标题')?.querySelector('input') as HTMLInputElement | undefined
  if (!title) throw new Error('找不到标题输入')
  title.value = '核实投诉工单'
  title.dispatchEvent(new Event('input', { bubbles: true }))
  await pickFromSelect('负责人', '7')
  const due = fieldByLabel('截止日期')?.querySelector('input') as HTMLInputElement | undefined
  if (!due) throw new Error('找不到截止日期')
  due.value = '2026-12-01'
  due.dispatchEvent(new Event('input', { bubbles: true }))
  await flushPromises()
}

function saveButton(): HTMLElement {
  const button = [...document.body.querySelectorAll('button')].find((item) =>
    item.textContent?.trim().startsWith('保存'),
  )
  if (!button) throw new Error('找不到保存按钮')
  return button as HTMLElement
}

describe('T08 建任务抽屉 · 项目型来源', () => {
  it('切到项目型：会议与说明消失，出现必选「来源项目」，形态提示跟随配置', async () => {
    mountDrawer({ open: true, sourceTypes: SOURCE_CONFIG, projects: PROJECTS })
    expect(fieldByLabel('来源会议'), '会议型才有的字段').toBeTruthy()

    await pickFromSelect('来源类型', 'S20001')

    expect(fieldByLabel('来源会议'), '项目型不再出现会议字段').toBeUndefined()
    expect(fieldByLabel('来源说明'), '项目型不再出现说明字段').toBeUndefined()
    expect(fieldByLabel('来源项目'), '项目型必须有项目字段').toBeTruthy()
    expect(document.body.textContent).toContain('当前形态：需绑定项目')
  })

  it('没选项目不能提交：本地拦下并给红字，不打服务端', async () => {
    mountDrawer({ open: true, sourceTypes: SOURCE_CONFIG, projects: PROJECTS })
    await pickFromSelect('来源类型', 'S20001')
    await fillBasics()

    saveButton().click()
    await flushPromises()

    expect(taskApiMock.create).not.toHaveBeenCalled()
    expect(document.body.textContent).toContain('请选择来源项目')
  })

  it('选了项目后 payload 只带 sourceProjectId（没有会议 / 说明字段）', async () => {
    mountDrawer({ open: true, sourceTypes: SOURCE_CONFIG, projects: PROJECTS })
    await pickFromSelect('来源类型', 'S20001')
    await fillBasics()
    await pickProject('老旧小区改造')

    taskApiMock.create.mockResolvedValueOnce({ id: 31 })
    saveButton().click()
    await flushPromises()

    expect(taskApiMock.create).toHaveBeenCalledTimes(1)
    // 以真正过线的 JSON 形态断言：值为 undefined 的键不会出现在请求体里
    const sent = JSON.parse(JSON.stringify(taskApiMock.create.mock.calls[0]![0]))
    expect(sent).toMatchObject({ sourceType: 'S20001', sourceProjectId: 12 })
    expect('sourceMeetingId' in sent).toBe(false)
    expect('sourceDecisionItemId' in sent).toBe(false)
    expect('sourceNote' in sent).toBe(false)
  })

  it('切到项目型再切回说明型：项目位清空，payload 不带 sourceProjectId', async () => {
    mountDrawer({ open: true, sourceTypes: SOURCE_CONFIG, projects: PROJECTS })
    await pickFromSelect('来源类型', 'S20001')
    await fillBasics()
    await pickProject('老旧小区改造')

    await pickFromSelect('来源类型', 'NOTE_TYPE')
    expect(fieldByLabel('来源项目'), '说明型不渲染项目位').toBeUndefined()

    const note = fieldByLabel('来源说明')?.querySelector('textarea') as
      | HTMLTextAreaElement
      | undefined
    if (!note) throw new Error('找不到来源说明')
    note.value = '电话转来的投诉'
    note.dispatchEvent(new Event('input', { bubbles: true }))
    await flushPromises()

    taskApiMock.create.mockResolvedValueOnce({ id: 32 })
    saveButton().click()
    await flushPromises()

    const sent = JSON.parse(JSON.stringify(taskApiMock.create.mock.calls[0]![0]))
    expect(sent.sourceType).toBe('NOTE_TYPE')
    expect(sent.sourceNote).toBe('电话转来的投诉')
    expect('sourceProjectId' in sent).toBe(false)
  })

  it('没有可用项目：字段不隐藏但禁用，并给出找管理员的指引', async () => {
    mountDrawer({ open: true, sourceTypes: SOURCE_CONFIG, projects: [] })
    await pickFromSelect('来源类型', 'S20001')

    const field = fieldByLabel('来源项目')
    expect(field, '无项目时字段仍在（避免表单项跳动）').toBeTruthy()
    const input = field!.querySelector('input[role="combobox"]') as HTMLInputElement | null
    expect(input?.disabled).toBe(true)
    expect(document.body.textContent).toContain('还没有可用项目。请联系管理员在「系统设置 → 项目配置」添加项目。')
  })

  it('编辑态项目型任务：只读回显项目名与停用后缀，不出现来源下拉', () => {
    const task = {
      id: 7,
      title: '历史投诉',
      priority: 'MEDIUM',
      dueDate: '2026-12-01',
      source: {
        type: 'S20001',
        typeLabel: '客户投诉',
        form: 'PROJECT',
        projectId: 9,
        projectName: '停用项目',
        projectEnabled: false,
      },
    } as unknown as TaskDetail
    mountDrawer({ open: true, task, sourceTypes: SOURCE_CONFIG, projects: PROJECTS })

    expect(sourceSelect(), '编辑态来源只读').toBeUndefined()
    expect(
      document.body.querySelector('input[role="combobox"]'),
      '编辑态不提供改项目入口（DESIGN §3.4）',
    ).toBeNull()
    expect(document.body.textContent).toContain('不可修改（含来源项目）')
    const rows = [...document.body.querySelectorAll('.source-readonly')].map((row) => ({
      label: row.querySelector('.source-readonly__label')?.textContent?.trim(),
      value: row.querySelector('.source-readonly__value')?.textContent?.trim(),
    }))
    expect(rows).toEqual([
      { label: '来源类型', value: '客户投诉' },
      { label: '来源项目', value: '停用项目（已停用）' },
    ])
  })

  it('编辑态改形历史（form=PROJECT 但无项目）：只读回显「未指定项目」', () => {
    const task = {
      id: 8,
      title: '改形后的历史任务',
      priority: 'MEDIUM',
      dueDate: '2026-12-01',
      source: { type: 'S20001', typeLabel: '客户投诉', form: 'PROJECT' },
    } as unknown as TaskDetail
    mountDrawer({ open: true, task, sourceTypes: SOURCE_CONFIG, projects: PROJECTS })

    expect(document.body.querySelector('input[role="combobox"]')).toBeNull()
    const values = [...document.body.querySelectorAll('.source-readonly__value')].map((row) =>
      row.textContent?.trim(),
    )
    expect(values).toEqual(['客户投诉', '未指定项目'])
  })

  it('项目清单已过期（服务端 400 PROJECT_DISABLED）：清空选择并请父级重拉 meta', async () => {
    const wrapper = mountDrawer({ open: true, sourceTypes: SOURCE_CONFIG, projects: PROJECTS })
    await pickFromSelect('来源类型', 'S20001')
    await fillBasics()
    await pickProject('老旧小区改造')

    taskApiMock.create.mockRejectedValueOnce(
      new ApiError({
        status: 400,
        code: 'PROJECT_DISABLED',
        message: '该项目已停用，请重新选择',
      }),
    )
    saveButton().click()
    await flushPromises()

    // 字段被清空：combobox 回到占位态，选中项不再显示
    const control = fieldByLabel('来源项目')?.querySelector('.field__control')
    expect(control?.textContent).not.toContain('老旧小区改造')
    expect(wrapper.emitted('refresh-meta')).toHaveLength(1)
    expect(wrapper.emitted('saved')).toBeUndefined()
    const toasts = useUiStore().toasts.map((item) => item.message)
    expect(toasts).toContain('该项目已停用，请重新选择')
  })
})
