// @vitest-environment jsdom
/**
 * 项目配置卡与项目弹窗（r4 T06 / DESIGN §3.1–3.2、TECHNICAL §4.4；TECHNICAL §8 点名的缺失件）。
 *
 * 钉住四条交付契约：
 * 1. 表格只读服务端 `usageCount`：被引用行不给「删除」，用「—」+ 说明代替，不靠 409 红框兜底；
 * 2. 行内排序 0–99 本地校验，越界不请求且回滚；启停/删除成功后只 emit refresh（页面不本地改数组）；
 * 3. 200 个时「+ 新建项目」禁用并给出可见原因；
 * 4. 弹窗名称 2–20 字、编码只读；409 重名落名称字段，409 上限落顶部摘要并禁用保存。
 */
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import type { ProjectRow } from '@/types/api'

const mocks = vi.hoisted(() => ({
  list: vi.fn(),
  create: vi.fn(),
  update: vi.fn(),
  setEnabled: vi.fn(),
  remove: vi.fn(),
}))

vi.mock('@/api/projects', () => ({ projectsApi: mocks }))

const { ApiError } = await import('@/api/http')
const { useUiStore } = await import('@/stores/ui')
const ProjectConfigTable = (await import('@/components/settings/ProjectConfigTable.vue')).default
const ProjectFormModal = (await import('@/components/settings/ProjectFormModal.vue')).default

function row(over: Partial<ProjectRow> & { id: number }): ProjectRow {
  const id = over.id
  return {
    code: `P${String(id).padStart(5, '0')}`,
    name: `项目${id}`,
    sort: 1,
    enabled: true,
    usageCount: 0,
    ...over,
  }
}

function mountTable(props: Record<string, unknown>, attach = false) {
  return mount(ProjectConfigTable, {
    props: { items: [], ...props },
    ...(attach ? { attachTo: document.body } : {}),
  })
}

function mountModal(props: Record<string, unknown>) {
  return mount(ProjectFormModal, {
    props: { open: true, ...props },
    attachTo: document.body,
  })
}

function buttonByText(text: string): HTMLButtonElement | undefined {
  return Array.from(document.body.querySelectorAll('button')).find(
    (item) => (item.textContent ?? '').trim() === text,
  ) as HTMLButtonElement | undefined
}

function modalInputs(): HTMLInputElement[] {
  return Array.from(document.body.querySelectorAll<HTMLInputElement>('.modal input'))
}

async function typeName(value: string): Promise<void> {
  const input = modalInputs()[0]
  input.value = value
  input.dispatchEvent(new Event('input'))
  await flushPromises()
}

beforeEach(() => {
  setActivePinia(createPinia())
  document.body.innerHTML = ''
  vi.clearAllMocks()
})

describe('ProjectConfigTable 项目配置表', () => {
  it('渲染名称/编码/状态/引用；被引用行只给「停用」，用「—」+ 说明代替删除', () => {
    const used = row({ id: 1, name: '轨道交通项目', usageCount: 4 })
    const free = row({ id: 2, name: '老旧小区改造', usageCount: 0, enabled: false })
    const wrapper = mountTable({ items: [used, free] })

    const rows = wrapper.findAll('tbody tr')
    expect(rows).toHaveLength(2)
    expect(rows[0].text()).toContain('轨道交通项目')
    expect(rows[0].text()).toContain('P00001')
    expect(rows[0].text()).toContain('启用')
    expect(rows[0].text()).toContain('4 条')

    const usedActions = rows[0].findAll('button').map((item) => item.text())
    expect(usedActions).toContain('编辑')
    expect(usedActions).toContain('停用')
    expect(usedActions).not.toContain('删除')
    expect(rows[0].find('.row-actions__blocked').attributes('title')).toBe(
      '已被 4 条任务使用，只能停用',
    )

    const freeActions = rows[1].findAll('button').map((item) => item.text())
    expect(freeActions).toContain('启用')
    expect(freeActions).toContain('删除')
    expect(rows[1].text()).toContain('已停用')
    wrapper.unmount()
  })

  it('行内排序：合法值 PUT name/sort/enabled 并 refresh；越界不请求且回滚', async () => {
    const ui = useUiStore()
    const target = row({ id: 7, name: '城市更新', sort: 3 })
    mocks.update.mockResolvedValue({ ...target, sort: 9 })
    const wrapper = mountTable({ items: [target] })

    const input = wrapper.find('input.sort-input')
    expect(input.attributes('aria-label')).toBe('排序：城市更新')

    await input.setValue('120')
    await input.trigger('blur')
    await flushPromises()
    expect(mocks.update).not.toHaveBeenCalled()
    expect((input.element as HTMLInputElement).value).toBe('3')
    expect(ui.toasts.at(-1)?.message).toBe('排序需在 0–99 之间')

    await input.setValue('9')
    await input.trigger('blur')
    await flushPromises()
    expect(mocks.update).toHaveBeenCalledWith(7, { name: '城市更新', sort: 9, enabled: true })
    expect(wrapper.emitted('refresh')).toHaveLength(1)
    wrapper.unmount()
  })

  it('启停调 setEnabled(!enabled)，成功文案说明影响面并 refresh', async () => {
    const ui = useUiStore()
    const target = row({ id: 5, name: '道路提升', enabled: true })
    mocks.setEnabled.mockResolvedValue({ ...target, enabled: false })
    const wrapper = mountTable({ items: [target] })

    const stop = wrapper.findAll('button').find((item) => item.text() === '停用')
    await stop?.trigger('click')
    await flushPromises()

    expect(mocks.setEnabled).toHaveBeenCalledWith(5, false)
    expect(ui.toasts.at(-1)?.message).toBe('已停用「道路提升」，新建任务不再可选该项目')
    expect(wrapper.emitted('refresh')).toHaveLength(1)
    wrapper.unmount()
  })

  it('删除需二次确认，确认后 DELETE 并 refresh', async () => {
    const target = row({ id: 4, name: '临时项目', usageCount: 0 })
    mocks.remove.mockResolvedValue(undefined)
    const wrapper = mountTable({ items: [target] }, true)

    await wrapper.find('.link-action--danger').trigger('click')
    await flushPromises()
    expect(mocks.remove).not.toHaveBeenCalled()
    expect(document.body.textContent).toContain('删除项目「临时项目」？')

    const confirm = document.body.querySelector<HTMLButtonElement>('.confirm__danger')
    expect(confirm).toBeTruthy()
    confirm?.click()
    await flushPromises()

    expect(mocks.remove).toHaveBeenCalledWith(4)
    expect(wrapper.emitted('refresh')).toHaveLength(1)
    wrapper.unmount()
  })

  it('加载失败显示 role=alert 与「重试」，点击 emit refresh', async () => {
    const wrapper = mountTable({ error: '项目列表加载失败' })
    expect(wrapper.find('[role="alert"]').text()).toContain('项目列表加载失败')

    const retry = wrapper.findAll('button').find((item) => item.text() === '重试')
    await retry?.trigger('click')
    expect(wrapper.emitted('refresh')).toHaveLength(1)
    wrapper.unmount()
  })

  it('加载中给骨架 + aria-busy；空列表给空态与新建入口', async () => {
    const loading = mountTable({ loading: true })
    expect(loading.find('[aria-busy="true"]').exists()).toBe(true)
    loading.unmount()

    const empty = mountTable({ items: [] })
    expect(empty.text()).toContain('还没有项目。新建后即可在「需绑定项目」的来源里选到。')
    const create = empty.findAll('button').find((item) => item.text() === '新建项目')
    await create?.trigger('click')
    await flushPromises()
    expect(document.body.querySelector('.modal')).toBeTruthy()
    empty.unmount()
  })

  it('达到 200 个时新增按钮禁用、aria-disabled 与可见原因同时出现', () => {
    const items = Array.from({ length: 200 }, (_, index) => row({ id: index + 1 }))
    const wrapper = mountTable({ items })

    const create = wrapper.findAll('button').find((item) => item.text().includes('新建项目'))
    expect(create?.attributes('disabled')).toBeDefined()
    expect(create?.attributes('aria-disabled')).toBe('true')
    expect(wrapper.text()).toContain('共 200 / 200 个项目')
    expect(wrapper.text()).toContain('项目最多 200 个，已达上限')
    wrapper.unmount()
  })
})

describe('ProjectFormModal 项目弹窗', () => {
  it('新建：名称不足 2 字不发请求并给字段错误', async () => {
    const wrapper = mountModal({ nextSort: 5 })
    await flushPromises()

    await typeName('城')
    buttonByText('保存')?.click()
    await flushPromises()

    expect(mocks.create).not.toHaveBeenCalled()
    expect(document.body.textContent).toContain('名称需为 2–20 个字')
    wrapper.unmount()
  })

  it('新建成功：payload 只带名称/排序/启用，编码占位不提交', async () => {
    const created = row({ id: 12, name: '城市更新一期', sort: 5 })
    mocks.create.mockResolvedValue(created)
    const wrapper = mountModal({ nextSort: 5 })
    await flushPromises()
    expect(document.body.textContent).toContain('保存后自动生成')

    await typeName('城市更新一期')
    buttonByText('保存')?.click()
    await flushPromises()

    expect(mocks.create).toHaveBeenCalledWith({ name: '城市更新一期', sort: 5, enabled: true })
    expect(wrapper.emitted('saved')?.[0]?.[0]).toMatchObject({ id: 12 })
    wrapper.unmount()
  })

  it('编辑：编码只读回显，保存走 update(id, …)', async () => {
    const target = row({ id: 3, name: '既有项目', sort: 2 })
    mocks.update.mockResolvedValue({ ...target, name: '改名后的项目' })
    const wrapper = mountModal({ row: target, nextSort: 9 })
    await flushPromises()

    expect(document.body.textContent).toContain('P00003')
    expect(document.body.textContent).toContain('创建后不可修改')
    expect(modalInputs()[0].value).toBe('既有项目')
    expect(modalInputs()[1].value).toBe('2')

    await typeName('改名后的项目')
    buttonByText('保存')?.click()
    await flushPromises()

    expect(mocks.update).toHaveBeenCalledWith(3, { name: '改名后的项目', sort: 2, enabled: true })
    wrapper.unmount()
  })

  it('409 重名落名称字段；409 上限落顶部摘要并禁用保存', async () => {
    mocks.create.mockRejectedValueOnce(
      new ApiError({ status: 409, code: 'PROJECT_NAME_DUPLICATE', message: '名称已存在' }),
    )
    const wrapper = mountModal({ nextSort: 0 })
    await flushPromises()

    await typeName('重复项目')
    buttonByText('保存')?.click()
    await flushPromises()
    expect(document.body.textContent).toContain('名称已存在，请换一个')
    expect(modalInputs()[0].getAttribute('aria-invalid')).toBe('true')

    mocks.create.mockRejectedValueOnce(
      new ApiError({
        status: 409,
        code: 'PROJECT_LIMIT_REACHED',
        message: '项目最多 200 个，已达上限',
      }),
    )
    buttonByText('保存')?.click()
    await flushPromises()

    expect(document.body.textContent).toContain('项目最多 200 个，已达上限')
    expect(buttonByText('保存')?.hasAttribute('disabled')).toBe(true)
    wrapper.unmount()
  })
})
