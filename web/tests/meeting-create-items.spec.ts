// @vitest-environment jsdom
/**
 * T7 建会批量录入（AC-1 / AC-2 / AC-3）。
 *
 * 钉住的接缝：
 * - 主持人仍是姓名文本：选系统用户会自动带进参会人一次，自由文本原样落库；
 * - 决议事项 0..N 行同一次请求提交，全空行不提交（0 行与 r6 行为完全一致）；
 * - 422 `items[K].field` 折算成「第 K 行 · 字段」落在行内单元格，焦点回到出错输入且已填内容不清空。
 */
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import { createPinia } from 'pinia'
import { createMemoryHistory, createRouter } from 'vue-router'
import type { MeetingDetail } from '@/types/api'

const meetingApiMock = vi.hoisted(() => ({
  list: vi.fn(),
  get: vi.fn(),
  create: vi.fn(),
  update: vi.fn(),
  archive: vi.fn(),
  addItem: vi.fn(),
  updateItem: vi.fn(),
  deleteItem: vi.fn(),
  convert: vi.fn(),
  tasks: vi.fn(),
}))
const metaApiMock = vi.hoisted(() => ({ options: vi.fn() }))

vi.mock('@/api/meetings', async (importOriginal) => {
  const actual = await importOriginal<typeof import('@/api/meetings')>()
  return { ...actual, meetingApi: meetingApiMock }
})
vi.mock('@/api/meta', () => ({ metaApi: metaApiMock }))
vi.mock('@/api/tasks', async (importOriginal) => {
  const actual = await importOriginal<typeof import('@/api/tasks')>()
  return actual
})

import { ApiError } from '@/api/http'
import MeetingsPage from '@/pages/MeetingsPage.vue'

function detail(over: Partial<MeetingDetail> = {}): MeetingDetail {
  return {
    id: 5,
    title: '十一月项目推进会',
    meetingDate: '2026-11-12',
    host: '张伟',
    archived: false,
    taskCount: 0,
    unfinishedCount: 0,
    closed: false,
    attendees: [],
    note: null,
    items: [],
    ...over,
  }
}

let wrapper: ReturnType<typeof mount> | null = null

async function mountPage() {
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/meetings', component: MeetingsPage },
      { path: '/meetings/:id', component: { template: '<div class="meeting-stub">会议详情</div>' } },
    ],
  })
  await router.push('/meetings')
  await router.isReady()
  wrapper = mount(
    { template: '<router-view />' },
    {
      global: { plugins: [router, createPinia()], stubs: { teleport: true } },
      attachTo: document.body,
    },
  )
  await flushPromises()
  return { router }
}

function buttonByText(text: string) {
  const found = wrapper!.findAll('button').filter((btn) => btn.text() === text)
  if (!found.length) throw new Error(`找不到按钮：${text}`)
  return found[0]
}

async function openCreate(): Promise<void> {
  await buttonByText('新建会议').trigger('click')
  await flushPromises()
}

/** AppSearchSelect：先 mousedown 展开，再输入过滤，最后点选项 */
async function pickHost(query: string, optionText: string): Promise<void> {
  const label = wrapper!.findAll('label').find((node) => node.text().includes('主持人'))
  if (!label) throw new Error('找不到「主持人」字段')
  const id = label.attributes('for')
  const input = wrapper!.find(`#${id}`)
  await input.trigger('mousedown')
  await flushPromises()
  await input.setValue(query)
  await flushPromises()
  const option = wrapper!.findAll('.option').find((node) => node.text().includes(optionText))
  if (!option) throw new Error(`找不到主持人选项：${optionText}`)
  await option.trigger('click')
  await flushPromises()
}

function cell(row: number, field: string) {
  return wrapper!.find(`[data-cell="items:${row}:${field}"]`)
}

async function fillDecision(
  row: number,
  values: { description?: string; ownerId?: string; dueDate?: string },
): Promise<void> {
  if (values.description !== undefined) await cell(row, 'description').find('input').setValue(values.description)
  if (values.ownerId !== undefined) await cell(row, 'ownerId').find('select').setValue(values.ownerId)
  if (values.dueDate !== undefined) await cell(row, 'dueDate').find('input').setValue(values.dueDate)
}

async function submit(): Promise<void> {
  await wrapper!.find('form').trigger('submit')
  await flushPromises()
}

beforeEach(() => {
  vi.clearAllMocks()
  metaApiMock.options.mockResolvedValue({
    owners: [
      { id: 3, name: '李负责', role: 'OWNER', status: 'ACTIVE' },
      { id: 4, name: '王执行', role: 'EXEC', status: 'ACTIVE' },
    ],
    meetings: [],
    sourceTypes: [],
    statuses: [],
    priorities: [],
    themes: [],
  })
  meetingApiMock.list.mockResolvedValue({ items: [], total: 0, page: 1, size: 20 })
  meetingApiMock.create.mockResolvedValue(detail())
})

afterEach(() => {
  wrapper?.unmount()
  wrapper = null
  document.body.innerHTML = ''
})

describe('MeetingsPage 建会登记', () => {
  it('选中系统用户为主持人时，自动带进参会人一次', async () => {
    await mountPage()
    await openCreate()
    expect(wrapper!.findAll('.multi__chip')).toHaveLength(0)

    await pickHost('李', '李负责')

    const chips = wrapper!.findAll('.multi__chip-text').map((chip) => chip.text())
    expect(chips).toEqual(['李负责'])
  })

  it('决议事项与会议同一次提交，全空行不提交', async () => {
    await mountPage()
    await openCreate()

    await wrapper!.find('input[placeholder="例如：十月项目推进会"]').setValue('十一月项目推进会')
    const dateInputs = wrapper!.findAll('input[type="date"]')
    await dateInputs[0].setValue('2026-11-12')
    await pickHost('李', '李负责')

    await fillDecision(0, { description: '完成灰度发布方案', ownerId: '3', dueDate: '2026-11-20' })
    await buttonByText('+ 添加一条').trigger('click')
    await flushPromises()
    await fillDecision(1, { description: '补齐验收材料', ownerId: '4', dueDate: '2026-11-25' })
    await buttonByText('+ 添加一条').trigger('click')
    await flushPromises()

    await submit()

    expect(meetingApiMock.create).toHaveBeenCalledTimes(1)
    const payload = meetingApiMock.create.mock.calls[0][0]
    expect(payload.title).toBe('十一月项目推进会')
    expect(payload.host).toBe('李负责')
    expect(payload.attendeeIds).toEqual([3])
    expect(payload.items).toEqual([
      { description: '完成灰度发布方案', ownerId: 3, dueDate: '2026-11-20' },
      { description: '补齐验收材料', ownerId: 4, dueDate: '2026-11-25' },
    ])
  })

  it('不录决议时请求里不带 items（0 行行为与 r6 一致）', async () => {
    await mountPage()
    await openCreate()
    await wrapper!.find('input[placeholder="例如：十月项目推进会"]').setValue('十一月项目推进会')
    await wrapper!.findAll('input[type="date"]')[0].setValue('2026-11-12')
    await pickHost('李', '李负责')

    await submit()

    const payload = meetingApiMock.create.mock.calls[0][0]
    // 不录决议时 items 为 undefined（序列化时不会出现在请求体里），与 r6 的建会请求完全一致
    expect(payload.items).toBeUndefined()
  })

  it('422 items[2].dueDate 落到第 2 行「截止日期」并聚焦，已填内容不清空', async () => {
    meetingApiMock.create.mockRejectedValue(
      new ApiError({
        status: 422,
        code: 'VALIDATION_FAILED',
        message: '还有 1 项需要修改',
        fieldErrors: [{ field: 'items[2].dueDate', message: '截止日期不能为空' }],
      }),
    )
    await mountPage()
    await openCreate()
    await wrapper!.find('input[placeholder="例如：十月项目推进会"]').setValue('十一月项目推进会')
    await wrapper!.findAll('input[type="date"]')[0].setValue('2026-11-12')
    await pickHost('李', '李负责')

    await fillDecision(0, { description: '完成灰度发布方案', ownerId: '3', dueDate: '2026-11-20' })
    await buttonByText('+ 添加一条').trigger('click')
    await flushPromises()
    await fillDecision(1, { description: '补齐验收材料', ownerId: '4', dueDate: '2026-11-25' })

    await submit()

    const secondRow = wrapper!.findAll('article.item')[1]
    expect(secondRow.text()).toContain('第 2 行：截止日期不能为空')
    expect(secondRow.classes()).toContain('item--invalid')
    // 已填内容不被清空
    expect((cell(1, 'description').find('input').element as HTMLInputElement).value).toBe('补齐验收材料')
    expect((cell(1, 'ownerId').find('select').element as HTMLSelectElement).value).toBe('4')
    // 焦点回到出错单元格
    const dueInput = cell(1, 'dueDate').find('input').element as HTMLInputElement
    expect(document.activeElement).toBe(dueInput)
  })
})
