// @vitest-environment jsdom
/**
 * T07 来源下拉配置化（AC-5 / DESIGN §3.3、§3.7）。
 *
 * 钉住两处最容易被写回枚举的接缝：
 * - 筛选条：选项全部来自 meta.sourceTypes，顺序为「启用（按 sort）→ 停用（按 sort）」，
 *   停用来源仍留在下拉里并标注「（已停用）」——停用不能等于筛不到历史任务；
 * - 建任务抽屉：新建只列启用项、形态随来源联动；编辑态来源只读展示（D6），不回写来源。
 */
import { afterEach, describe, expect, it, vi } from 'vitest'
import { mount } from '@vue/test-utils'
import { createPinia } from 'pinia'
import TaskFilterBar, { type PoolFilters } from '@/components/task/TaskFilterBar.vue'
import TaskFormDrawer from '@/components/task/TaskFormDrawer.vue'
import type { SourceTypeOption, TaskDetail, UserOption } from '@/types/api'

const taskApiMock = vi.hoisted(() => ({
  decisionItems: vi.fn().mockResolvedValue([]),
  create: vi.fn(),
  update: vi.fn(),
}))

vi.mock('@/api/tasks', () => ({ taskApi: taskApiMock }))

function option(over: Partial<SourceTypeOption>): SourceTypeOption {
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
  option({ value: 'S10002', label: '复盘事项', enabled: false, sort: 3, form: 'NOTE' }),
  option({ value: 'MEETING', label: '会议决议', enabled: true, sort: 1 }),
  option({ value: 'PROJECT', label: '项目任务', enabled: true, sort: 2, form: 'NOTE' }),
  option({ value: 'S10001', label: '历史专项', enabled: false, sort: 9, form: 'NOTE' }),
]

const FILTERS: PoolFilters = {
  status: '',
  ownerId: '',
  sourceType: '',
  meetingId: '',
  overdue: '',
  keyword: '',
}

function mountBar(modelValue: PoolFilters = FILTERS) {
  return mount(TaskFilterBar, {
    props: { modelValue, owners: [], meetings: [], sourceTypes: SOURCE_CONFIG },
  })
}

/** 取 aria-label 为「来源类型筛选」的那个下拉（筛选条里字段多，按无障碍名定位最稳） */
function sourceSelect(wrapper: ReturnType<typeof mountBar>) {
  const select = wrapper.find('[aria-label="来源类型筛选"] select')
  expect(select.exists(), '来源下拉存在').toBe(true)
  return select
}

function sourceOptionLabels(wrapper: ReturnType<typeof mountBar>): string[] {
  return sourceSelect(wrapper).findAll('option').map((opt) => opt.text())
}

describe('T07 任务池来源筛选', () => {
  it('选项来自配置：全部来源在前，启用按 sort，停用排在后面并标注（已停用）', () => {
    const labels = sourceOptionLabels(mountBar())
    expect(labels).toEqual([
      '全部来源',
      '会议决议',
      '项目任务',
      '复盘事项（已停用）',
      '历史专项（已停用）',
    ])
  })

  it('停用来源仍可选中，且照常回传编码（停用≠筛不到历史）', async () => {
    const wrapper = mountBar()
    await sourceSelect(wrapper).setValue('S10001')
    const emitted = wrapper.emitted('update:modelValue') ?? []
    expect(emitted.length).toBeGreaterThan(0)
    expect((emitted.at(-1)![0] as PoolFilters).sourceType).toBe('S10001')
  })

  it('没有配置数据时只剩「全部来源」，不会退回写死的三类', () => {
    const wrapper = mount(TaskFilterBar, {
      props: { modelValue: FILTERS, owners: [], meetings: [], sourceTypes: [] },
    })
    const labels = sourceOptionLabels(wrapper)
    expect(labels).toEqual(['全部来源'])
  })
})

/** 抽屉走 Teleport 到 body，断言要查真实 document（mount 时 attachTo 一并挂上） */
const mounted: Array<{ unmount: () => void }> = []
afterEach(() => {
  mounted.splice(0).forEach((item) => item.unmount())
  document.body.innerHTML = ''
})

interface DrawerProps {
  open: boolean
  task?: TaskDetail | null
  owners: UserOption[]
  meetings: Array<{ id: number; title: string }>
  sourceTypes?: SourceTypeOption[]
}

function mountDrawer(props: DrawerProps) {
  const wrapper = mount(TaskFormDrawer, {
    props,
    attachTo: document.body,
    global: { plugins: [createPinia()] },
  })
  mounted.push(wrapper)
  return wrapper
}

function drawerSourceSelect(): HTMLSelectElement | undefined {
  const label = [...document.body.querySelectorAll('.field__label')].find((node) =>
    node.textContent?.startsWith('来源类型'),
  )
  return (label?.parentElement?.querySelector('select') ?? undefined) as
    | HTMLSelectElement
    | undefined
}

const EDIT_TASK = {
  id: 7,
  title: '历史专项复盘',
  priority: 'MEDIUM',
  dueDate: '2026-12-01',
  source: { type: 'S10001', typeLabel: '历史专项', note: '线下评审结论' },
} as unknown as TaskDetail

describe('T07 建任务抽屉来源组', () => {
  it('新建态只列启用来源，形态提示随配置联动', () => {
    mountDrawer({ open: true, sourceTypes: SOURCE_CONFIG, owners: [], meetings: [] })
    const select = drawerSourceSelect()
    expect(select, '新建态有来源下拉').toBeTruthy()
    expect([...select!.options].map((opt) => opt.textContent?.trim())).toEqual([
      '会议决议',
      '项目任务',
    ])
    expect(document.body.textContent).toContain('当前形态：需绑定会议')
  })

  it('编辑态来源只读展示配置名称，同页不再出现来源下拉（D6）', () => {
    mountDrawer({
      open: true,
      task: EDIT_TASK,
      sourceTypes: SOURCE_CONFIG,
      owners: [],
      meetings: [],
    })
    expect(document.body.querySelector('.source-readonly__value')?.textContent?.trim()).toBe(
      '历史专项（已停用）',
    )
    expect(drawerSourceSelect()).toBeUndefined()
    expect(document.body.textContent).toContain('来源在创建时确定，不可修改')
  })
})
