// @vitest-environment jsdom
/**
 * 主题配置表（r7 T5 / DESIGN §3.2–§3.3）。
 *
 * 只替身网络层，真实挂载组件：行内开关与排序提交整行 payload、被引用时删除不可用、
 * 上限时新建禁用、409 名称重复回到字段错误、非启用项仍可编辑。
 */
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import { createPinia } from 'pinia'
import type { ThemeRow } from '@/types/api'

const api = vi.hoisted(() => ({
  list: vi.fn(),
  palette: vi.fn(),
  create: vi.fn(),
  update: vi.fn(),
  setEnabled: vi.fn(),
  remove: vi.fn(),
}))

vi.mock('@/api/themes', () => ({ themesApi: api }))

const ThemeConfigTable = (await import('@/components/settings/ThemeConfigTable.vue')).default

const PALETTE = [
  { color: '#175cd3', soft: '#eff8ff', border: '#b2ddff', name: '墨蓝' },
  { color: '#b42318', soft: '#fef3f2', border: '#fecdca', name: '砖红' },
]

function row(overrides: Partial<ThemeRow> = {}): ThemeRow {
  return {
    id: 1,
    name: '安全',
    color: '#175cd3',
    softColor: '#eff8ff',
    borderColor: '#b2ddff',
    sort: 1,
    enabled: true,
    usageCount: 0,
    ...overrides,
  }
}

function mountTable(items: ThemeRow[]) {
  return mount(ThemeConfigTable, { props: { items }, attachTo: document.body, global: { plugins: [createPinia()] } })
}

function buttonByText(wrapper: ReturnType<typeof mountTable>, text: string) {
  return wrapper.findAll('button').find((item) => item.text().trim() === text)
}

describe('ThemeConfigTable', () => {
  beforeEach(() => {
    Object.values(api).forEach((fn) => fn.mockReset())
    api.palette.mockResolvedValue(PALETTE)
    api.setEnabled.mockResolvedValue(row({ enabled: false }))
    api.update.mockResolvedValue(row({ sort: 5 }))
    api.remove.mockResolvedValue(undefined)
    api.create.mockResolvedValue(row({ id: 9, name: '交付' }))
  })

  it('渲染主题行：色名与色块同现、引用数、停用后缀走 AppBadge 文案', async () => {
    const wrapper = mountTable([
      row({ id: 1, name: '安全', usageCount: 3 }),
      row({ id: 2, name: '交付', color: '#b42318', enabled: false }),
    ])
    await flushPromises()

    const rows = wrapper.findAll('tbody tr')
    expect(rows).toHaveLength(2)
    expect(rows[0].text()).toContain('安全')
    expect(rows[0].text()).toContain('墨蓝')
    expect(rows[0].text()).toContain('3')
    expect(rows[1].text()).toContain('已停用')
    // 色块内联使用主题自身颜色（jsdom 归一化成 rgb），色名与色块同现
    expect((rows[1].find('.cell-color__dot').element as HTMLElement).style.backgroundColor).toBe(
      'rgb(180, 35, 24)',
    )
    wrapper.unmount()
  })

  it('停用：调用 setEnabled 并请求刷新（不做本地状态覆盖）', async () => {
    const wrapper = mountTable([row({ id: 1, usageCount: 2 })])
    await flushPromises()
    const toggle = buttonByText(wrapper, '停用')
    expect(toggle).toBeTruthy()
    await toggle!.trigger('click')
    await flushPromises()
    expect(api.setEnabled).toHaveBeenCalledWith(1, false)
    expect(wrapper.emitted('refresh')).toHaveLength(1)
    wrapper.unmount()
  })

  it('排序提交：合法值发整行 update（名称与颜色原样保留），非法值只提示不回写', async () => {
    const wrapper = mountTable([row({ id: 4, name: '安全', sort: 1 })])
    await flushPromises()
    const input = wrapper.find('input.sort-input')

    await input.setValue('130')
    await input.trigger('blur')
    await flushPromises()
    expect(api.update).not.toHaveBeenCalled()
    expect((wrapper.vm as unknown as { $el: HTMLElement }).$el.textContent).not.toContain('130')

    await input.setValue('5')
    await input.trigger('blur')
    await flushPromises()
    expect(api.update).toHaveBeenCalledWith(4, {
      name: '安全',
      color: '#175cd3',
      sort: 5,
      enabled: true,
    })
    wrapper.unmount()
  })

  it('被引用（usageCount>0）：删除替换为不可点提示，标题说明只能停用', async () => {
    const wrapper = mountTable([row({ id: 3, usageCount: 7 })])
    await flushPromises()
    expect(buttonByText(wrapper, '删除')).toBeUndefined()
    const blocked = wrapper.find('.row-actions__blocked')
    expect(blocked.attributes('title')).toBe('该主题已被 7 条任务使用，只能停用')
    wrapper.unmount()
  })

  it('未被引用：删除需二次确认，确认后调用 remove', async () => {
    const wrapper = mountTable([row({ id: 3, usageCount: 0 })])
    await flushPromises()
    await buttonByText(wrapper, '删除')!.trigger('click')
    await flushPromises()
    // Modal 走 Teleport 到 body，查询要落到 document 上
    const confirm = document.body.querySelector<HTMLButtonElement>('.confirm__danger')
    expect(confirm).toBeTruthy()
    confirm!.click()
    await flushPromises()
    expect(api.remove).toHaveBeenCalledWith(3)
    wrapper.unmount()
  })

  it('达到 100 个上限：新建按钮禁用并给出上限文案', async () => {
    const items = Array.from({ length: 100 }, (_, index) => row({ id: index + 1, name: `主题${index}`, sort: index }))
    const wrapper = mountTable(items)
    await flushPromises()
    const create = buttonByText(wrapper, '+ 新建主题')
    expect(create?.attributes('disabled')).toBeDefined()
    expect(wrapper.find('.theme-config__bar').text()).toContain('100')
    wrapper.unmount()
  })

  it('新建：名称重复（409）回填到名称字段，弹窗不关闭', async () => {
    const { ApiError } = await import('@/api/http')
    api.create.mockRejectedValue(
      new ApiError({ status: 409, code: 'THEME_NAME_DUPLICATE', message: '主题名称已存在' }),
    )
    const wrapper = mountTable([row()])
    await flushPromises()
    await buttonByText(wrapper, '+ 新建主题')!.trigger('click')
    await flushPromises()

    const inputs = Array.from(document.body.querySelectorAll<HTMLInputElement>('.modal input'))
    expect(inputs.length).toBeGreaterThan(0)
    inputs[0].value = '安全'
    inputs[0].dispatchEvent(new Event('input'))
    const form = document.body.querySelector<HTMLFormElement>('.modal form')
    expect(form).toBeTruthy()
    form!.dispatchEvent(new Event('submit'))
    await flushPromises()

    expect(api.create).toHaveBeenCalledTimes(1)
    expect(document.body.textContent).toContain('名称已存在')
    expect(document.body.querySelector('.modal')).toBeTruthy()
    wrapper.unmount()
  })

  it('列结构：6 列表头与首行 6 个 td 一一对应，名称/颜色格的 flex 容器是 td 内层 DIV', async () => {
    const wrapper = mountTable([row(), row({ id: 2, name: '交付', color: '#b42318', enabled: false })])
    await flushPromises()

    const heads = wrapper.findAll('thead th').map((th) => th.text().trim())
    expect(heads).toEqual(['名称', '颜色', '状态', '排序', '引用', '操作'])

    const firstRow = wrapper.findAll('tbody tr')[0]
    const cells = firstRow.findAll('td')
    expect(cells).toHaveLength(heads.length)

    // A-02 回归：flex 必须落在 td 内层 div，td 自身保持 table-cell（jsdom 无布局，computed display 由 Playwright 探针负责）
    for (const [selector, innerClass] of [
      ['td.cell-name', 'cell-name__inner'],
      ['td.cell-color', 'cell-color__inner'],
    ] as const) {
      const td = firstRow.find(selector)
      expect(td.exists()).toBe(true)
      expect((td.element as HTMLElement).tagName).toBe('TD')
      expect(td.attributes('style') ?? '').not.toContain('flex')
      const inner = td.element.firstElementChild as HTMLElement
      expect(inner.tagName).toBe('DIV')
      expect(inner.classList.contains(innerClass)).toBe(true)
      // td 下只有这一层容器，内容不再直接挂在 td 上
      expect(td.element.children).toHaveLength(1)
    }

    // 内容未丢失：名称文字与色块仍在各自内层容器里
    expect(firstRow.find('.cell-name__inner .cell-name__text').text()).toBe('安全')
    expect(wrapper.findAll('tbody tr')[1].find('.cell-name__inner').text()).toContain('已停用')
    expect(firstRow.find('.cell-color__inner .cell-color__dot').exists()).toBe(true)
    expect(firstRow.find('.cell-color__inner').text()).toContain('墨蓝')
    wrapper.unmount()
  })
})
