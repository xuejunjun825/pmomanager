// @vitest-environment jsdom
/**
 * r6 建任务抽屉 · 会议形态「关联决议项」必填（AC-1…AC-6）。
 *
 * 只钉四类可观察证据：请求未发出 / 字段行内文案 / 请求载荷 / 焦点落位。
 * 不断言 `required` 属性、不断言组件内部变量——实现改了而行为没变，用例不应变红。
 *
 * 与 task-form-project.spec.ts 刻意不共享 helper（复制而非 import）：
 * 一个 spec 的失败不该牵连另一个形态的回归面。
 */
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import { createPinia } from 'pinia'
import TaskFormDrawer from '@/components/task/TaskFormDrawer.vue'
import { ApiError } from '@/api/http'
import type {
  DecisionItemRow,
  ProjectOptionRow,
  SourceTypeOption,
  TaskDetail,
  UserOption,
} from '@/types/api'

const taskApiMock = vi.hoisted(() => ({
  decisionItems: vi.fn(),
  create: vi.fn(),
  update: vi.fn(),
}))

vi.mock('@/api/tasks', () => ({ taskApi: taskApiMock }))

const DECISION_REQUIRED = '请选择关联决议项'
const DECISION_LOADING = '决议项加载中，请稍候'
const DECISION_LOAD_FAILED = '决议项加载失败，请点击下方「重试加载决议项」'
const DECISION_NONE_AVAILABLE =
  '该会议暂无可关联的决议项（均已成任务），请改选会议或改用其他来源'

/** 一条可关联的决议项（未转任务） */
const AVAILABLE_ITEM: DecisionItemRow = {
  id: 11,
  description: '补录第 5 条决议项',
  owner: { id: 7, name: '张三' },
  dueDate: '2026-12-01',
  sortNo: 1,
  converted: false,
  isOverdue: false,
  overdueDays: 0,
}

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

const PROJECTS: ProjectOptionRow[] = [{ id: 12, name: '老旧小区改造', enabled: true, sort: 1 }]
const OWNERS: UserOption[] = [{ id: 7, name: '张三', role: 'OWNER', status: 'ACTIVE' }]
const MEETINGS = [{ id: 1, title: '周例会' }]

const mounted: Array<{ unmount: () => void }> = []

afterEach(() => {
  mounted.splice(0).forEach((item) => item.unmount())
  document.body.innerHTML = ''
  vi.clearAllMocks()
})

beforeEach(() => {
  // 默认：会议下有 1 条可关联决议项，覆盖「正常可选」这条主路径
  taskApiMock.decisionItems.mockResolvedValue([AVAILABLE_ITEM])
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
  // 抽屉 Teleport 到 body：同一时刻只留一个实例，否则标签查询会命中上一次的残留
  mounted.splice(0).forEach((item) => item.unmount())
  const wrapper = mount(TaskFormDrawer, {
    props: {
      owners: OWNERS,
      meetings: MEETINGS,
      sourceTypes: SOURCE_CONFIG,
      projects: PROJECTS,
      ...props,
    },
    attachTo: document.body,
    global: { plugins: [createPinia()] },
  })
  mounted.push(wrapper)
  return wrapper
}

function fieldByLabel(label: string): HTMLElement | undefined {
  const node = [...document.body.querySelectorAll('.field__label')].find((item) =>
    item.textContent?.trim().startsWith(label),
  )
  return (node?.parentElement ?? undefined) as HTMLElement | undefined
}

/** 字段行内错误文案（服务端 message 或本地原因，都从这里读） */
function errorText(label: string): string {
  return fieldByLabel(label)?.querySelector('.field__error')?.textContent?.trim() ?? ''
}

async function pickFromSelect(label: string, value: string): Promise<void> {
  const select = fieldByLabel(label)?.querySelector('select') as HTMLSelectElement | undefined
  if (!select) throw new Error(`找不到下拉：${label}`)
  select.value = value
  select.dispatchEvent(new Event('change', { bubbles: true }))
  await flushPromises()
}

/** 真实交互：点开 combobox 再点选项（项目型来源用） */
async function pickProject(name: string): Promise<void> {
  const field = fieldByLabel('来源项目')
  if (!field) throw new Error('抽屉里没有「来源项目」字段')
  const control = field.querySelector('.field__control') as HTMLElement | null
  if (!control) throw new Error('「来源项目」字段没有可点区域')
  control.dispatchEvent(new MouseEvent('mousedown', { bubbles: true }))
  await flushPromises()
  const option = [...field.querySelectorAll('.list .option')].find((item) =>
    item.textContent?.includes(name),
  )
  if (!option) throw new Error(`项目下拉里找不到：${name}`)
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

function fillNote(value: string): void {
  const note = fieldByLabel('来源说明')?.querySelector('textarea') as HTMLTextAreaElement | undefined
  if (!note) throw new Error('找不到来源说明')
  note.value = value
  note.dispatchEvent(new Event('input', { bubbles: true }))
}

/** 把某形态的必填项全部填好（用于「只差服务端那一个字段」的场景） */
async function completeSource(form: 'MEETING' | 'PROJECT' | 'NOTE'): Promise<void> {
  if (form === 'PROJECT') await pickFromSelect('来源类型', 'S20001')
  if (form === 'NOTE') await pickFromSelect('来源类型', 'NOTE_TYPE')
  await fillBasics()
  if (form === 'MEETING') {
    await pickFromSelect('来源会议', '1')
    await pickFromSelect('关联决议项', '11')
  } else if (form === 'PROJECT') {
    await pickProject('老旧小区改造')
  } else {
    fillNote('电话转来的投诉')
    await flushPromises()
  }
}

function saveButton(): HTMLElement {
  const button = [...document.body.querySelectorAll('button')].find((item) =>
    item.textContent?.trim().startsWith('保存'),
  )
  if (!button) throw new Error('找不到保存按钮')
  return button as HTMLElement
}

async function clickSave(): Promise<void> {
  saveButton().click()
  await flushPromises()
}

/** 以真正过线的 JSON 形态取请求体：值为 undefined 的键不会出现在请求里 */
function sentBody(mock: { mock: { calls: unknown[][] } }, index = 0): Record<string, unknown> {
  return JSON.parse(JSON.stringify(mock.mock.calls[index]![0] ?? {})) as Record<string, unknown>
}

describe('建任务抽屉 · 会议决议项必填', () => {
  it('AC-1 会议形态：决议项带必填标记、初始为空，文案不暗示可以跳过', async () => {
    mountDrawer({ open: true })

    const field = fieldByLabel('关联决议项')
    expect(field, '会议形态必须有「关联决议项」字段').toBeTruthy()
    expect(field!.querySelector('.field__req'), '必填标记').toBeTruthy()
    const select = field!.querySelector('select') as HTMLSelectElement
    expect(select.value, '初始不预选任何决议项').toBe('')
    // placeholder 只能是「先去选会议」，不能出现「可不选」这类可选口径
    expect(select.selectedOptions[0]?.textContent?.trim()).toBe('请先选择来源会议')
    expect(document.body.textContent).not.toContain('可不选')
    expect(document.body.textContent).not.toContain('可以先只选择会议')
  })

  it('AC-1 未选决议项保存：0 次 POST、摘要与行内文案、焦点落在该字段', async () => {
    mountDrawer({ open: true })
    await fillBasics()
    await pickFromSelect('来源会议', '1')

    await clickSave()

    expect(taskApiMock.create, '本地必须拦下，不打服务端').not.toHaveBeenCalled()
    expect(document.body.textContent).toContain('还有 1 项需要填写')
    expect(errorText('关联决议项')).toBe(DECISION_REQUIRED)
    const field = fieldByLabel('关联决议项')!
    expect(field.contains(document.activeElement), '焦点必须落在决议项字段上').toBe(true)
  })

  it('AC-2 选齐后一次提交：载荷带会议与决议项，且不覆盖手填标题', async () => {
    mountDrawer({ open: true })
    await fillBasics()
    await pickFromSelect('来源会议', '1')
    await pickFromSelect('关联决议项', '11')

    const title = fieldByLabel('任务标题')!.querySelector('input') as HTMLInputElement
    expect(title.value, '用户已手填的标题不被决议项带出覆盖').toBe('核实投诉工单')

    taskApiMock.create.mockResolvedValueOnce({ id: 31 })
    await clickSave()

    expect(taskApiMock.create).toHaveBeenCalledTimes(1)
    const sent = sentBody(taskApiMock.create)
    expect(sent).toMatchObject({
      title: '核实投诉工单',
      sourceType: 'MEETING',
      sourceMeetingId: 1,
      sourceDecisionItemId: 11,
    })
    expect(typeof sent.sourceDecisionItemId).toBe('number')
    expect('sourceProjectId' in sent).toBe(false)
    expect('sourceNote' in sent).toBe(false)
  })

  const FORM_ROWS: Array<{
    form: 'MEETING' | 'PROJECT'
    label: string
    message: string
  }> = [
    { form: 'MEETING', label: '来源会议', message: '请选择来源会议' },
    { form: 'PROJECT', label: '来源项目', message: '请选择来源项目' },
  ]

  it.each(FORM_ROWS)(
    'AC-3 $form 形态只缺「$label」：本地拦截并给行内文案，0 次请求',
    async (row) => {
      mountDrawer({ open: true })
      if (row.form === 'PROJECT') await pickFromSelect('来源类型', 'S20001')
      await fillBasics()

      await clickSave()

      expect(taskApiMock.create).not.toHaveBeenCalled()
      expect(document.body.textContent).toContain('还有 1 项需要填写')
      expect(errorText(row.label)).toBe(row.message)
      const field = fieldByLabel(row.label)!
      expect(field.contains(document.activeElement), '焦点落在缺失字段上').toBe(true)
    },
  )

  it('AC-3 说明形态：空白与超长都被拦下，合法说明去首尾空格后提交', async () => {
    mountDrawer({ open: true })
    await pickFromSelect('来源类型', 'NOTE_TYPE')
    await fillBasics()

    await clickSave()
    expect(taskApiMock.create).not.toHaveBeenCalled()
    expect(errorText('来源说明')).toContain('请填写来源说明')

    fillNote('说'.repeat(201))
    await flushPromises()
    await clickSave()
    expect(taskApiMock.create).not.toHaveBeenCalled()
    expect(errorText('来源说明')).toBe('来源说明不超过 200 字')

    fillNote('  电话转来的投诉  ')
    await flushPromises()
    taskApiMock.create.mockResolvedValueOnce({ id: 32 })
    await clickSave()

    const sent = sentBody(taskApiMock.create)
    expect(sent.sourceNote).toBe('电话转来的投诉')
    expect(sent.sourceType).toBe('NOTE_TYPE')
    expect('sourceMeetingId' in sent).toBe(false)
    expect('sourceDecisionItemId' in sent).toBe(false)
    expect('sourceProjectId' in sent).toBe(false)
  })

  it('AC-4 服务端 422：四个来源字段都能落到字段行内并聚焦首个错误', async () => {
    const rows: Array<{
      serverField: string
      label: string
      message: string
      form: 'MEETING' | 'PROJECT' | 'NOTE'
    }> = [
      { serverField: 'sourceMeetingId', label: '来源会议', message: '来源会议已失效', form: 'MEETING' },
      {
        serverField: 'sourceDecisionItemId',
        label: '关联决议项',
        message: '决议项已转任务',
        form: 'MEETING',
      },
      { serverField: 'sourceProjectId', label: '来源项目', message: '项目已停用', form: 'PROJECT' },
      { serverField: 'sourceNote', label: '来源说明', message: '说明含敏感词', form: 'NOTE' },
    ]

    for (const row of rows) {
      // 表驱动每行都是一次独立提交：先清掉上一行的调用记录与一次性实现
      taskApiMock.create.mockReset()
      mountDrawer({ open: true })
      await completeSource(row.form)
      taskApiMock.create.mockRejectedValueOnce(
        new ApiError({
          status: 422,
          code: 'VALIDATION_FAILED',
          message: '参数校验失败',
          fieldErrors: [{ field: row.serverField, message: row.message }],
        }),
      )

      await clickSave()

      const where = `${row.serverField} → ${row.label}`
      expect(taskApiMock.create, where).toHaveBeenCalledTimes(1)
      expect(document.body.textContent, where).toContain('还有 1 项需要修改')
      expect(errorText(row.label), `${where} 的行内文案`).toBe(row.message)
      const field = fieldByLabel(row.label)!
      expect(field.contains(document.activeElement), `${where} 的焦点`).toBe(true)
    }
  })

  it('AC-5 编辑态会议任务：来源只读回显会议与决议项，PUT 载荷不含 source*', async () => {
    const task = {
      id: 7,
      title: '历史会议任务',
      priority: 'MEDIUM',
      dueDate: '2026-12-01',
      owner: { id: 7, name: '张三' },
      source: {
        type: 'MEETING',
        typeLabel: '会议决议',
        form: 'MEETING',
        meetingId: 1,
        decisionItemId: 11,
      },
    } as unknown as TaskDetail
    mountDrawer({ open: true, task })
    await flushPromises()

    const rows = [...document.body.querySelectorAll('.source-readonly')].map((item) => ({
      label: item.querySelector('.source-readonly__label')?.textContent?.trim(),
      value: item.querySelector('.source-readonly__value')?.textContent?.trim(),
    }))
    expect(rows, '会议与决议项都要以名称回显，不能空白').toContainEqual({
      label: '来源会议',
      value: '周例会',
    })
    expect(rows).toContainEqual({ label: '关联决议项', value: AVAILABLE_ITEM.description })
    expect(fieldByLabel('来源类型'), '编辑态来源不可改').toBeUndefined()
    expect(document.body.textContent).not.toContain(DECISION_REQUIRED)
    expect(taskApiMock.decisionItems, '回显只走一个加载入口，不重复请求').toHaveBeenCalledTimes(1)

    taskApiMock.update.mockResolvedValueOnce({ id: 7 })
    await clickSave()

    expect(taskApiMock.update).toHaveBeenCalledTimes(1)
    // update(id, payload)：载荷是第二个实参
    const sent = JSON.parse(
      JSON.stringify(taskApiMock.update.mock.calls[0]![1]),
    ) as Record<string, unknown>
    // r7 起编辑态恒带 themeId/计划开始字段（0 与 clearPlannedStart 表示清空），但仍不含 source*
    expect(sent).toEqual({
      title: '历史会议任务',
      priority: 'MEDIUM',
      themeId: 0,
      clearPlannedStart: true,
    })
    expect(Object.keys(sent).some((key) => key.startsWith('source'))).toBe(false)
    expect(taskApiMock.create).not.toHaveBeenCalled()
  })

  it('AC-6 决议项全部已转任务：行内说明原因、控件禁用、保存不提交', async () => {
    taskApiMock.decisionItems.mockResolvedValueOnce([
      { ...AVAILABLE_ITEM, converted: true, taskId: 99 },
    ])
    mountDrawer({ open: true })
    await fillBasics()
    await pickFromSelect('来源会议', '1')

    const select = fieldByLabel('关联决议项')!.querySelector('select') as HTMLSelectElement
    expect(select.disabled, '无可选决议项时控件禁用').toBe(true)
    expect(errorText('关联决议项'), '说的是原因，不是「你漏选了」').toBe(DECISION_NONE_AVAILABLE)
    expect(document.body.textContent).not.toContain(DECISION_REQUIRED)

    await clickSave()
    expect(taskApiMock.create).not.toHaveBeenCalled()
    expect(document.body.textContent).toContain('还有 1 项需要填写')
  })

  it('AC-6 决议项加载失败：行内可解释并可重试，重试成功后能正常提交', async () => {
    taskApiMock.decisionItems.mockRejectedValueOnce(new Error('network down'))
    mountDrawer({ open: true })
    await fillBasics()
    await pickFromSelect('来源会议', '1')

    expect(errorText('关联决议项')).toBe(DECISION_LOAD_FAILED)
    await clickSave()
    expect(taskApiMock.create, '加载失败时保存不提交').not.toHaveBeenCalled()

    const retry = [...document.body.querySelectorAll('button')].find((item) =>
      item.textContent?.includes('重试加载决议项'),
    )
    expect(retry, '失败必须给重试入口').toBeTruthy()
    ;(retry as HTMLElement).click()
    await flushPromises()

    const select = fieldByLabel('关联决议项')!.querySelector('select') as HTMLSelectElement
    expect(select.disabled, '重试成功后控件恢复可用').toBe(false)
    await pickFromSelect('关联决议项', '11')

    taskApiMock.create.mockResolvedValueOnce({ id: 41 })
    await clickSave()
    expect(taskApiMock.create).toHaveBeenCalledTimes(1)
    expect(sentBody(taskApiMock.create).sourceDecisionItemId).toBe(11)
  })

  it('AC-6 决议项加载中保存：提示等待加载，不误判为「未选择」也不提交', async () => {
    taskApiMock.decisionItems.mockReturnValueOnce(new Promise<DecisionItemRow[]>(() => {}))
    mountDrawer({ open: true })
    await fillBasics()
    await pickFromSelect('来源会议', '1')

    expect(errorText('关联决议项')).toBe(DECISION_LOADING)
    await clickSave()
    expect(taskApiMock.create).not.toHaveBeenCalled()
    expect(document.body.textContent).toContain('还有 1 项需要填写')
    expect(document.body.textContent).not.toContain(DECISION_REQUIRED)
  })
})
