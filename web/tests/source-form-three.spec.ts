// @vitest-environment jsdom
/**
 * T07 / AC-6 改形知情门禁（DESIGN §3.3、TECHNICAL §4.5）：
 * 来源弹窗形态三选一（自由说明 → 需绑定会议 → 需绑定项目，顺序固定）；
 * 改形且该来源已被任务使用（usageCount > 0）时必须出「不迁移」提示块 + 必勾确认：
 * 未勾不可保存、主按钮文案变「确认改形并保存」；形态未变、usage = 0 或改回原形态时不出现。
 *
 * 附带一条静态几何门禁：弹窗 560 宽 + 三卡并排（Modal 只有 md=480/lg=640 两档，
 * 560 由本组件用 :has 认领自己的面板，改了就容易在评审时回归成 480）。
 */
import { describe, expect, it, vi } from 'vitest'
import { existsSync, readFileSync } from 'node:fs'
import { resolve } from 'node:path'
import { defineComponent, h } from 'vue'
import { flushPromises, mount } from '@vue/test-utils'

const apiMocks = vi.hoisted(() => ({
  list: vi.fn(),
  create: vi.fn(),
  update: vi.fn(),
}))

vi.mock('@/api/sourceTypes', () => ({ sourceTypesApi: apiMocks }))

import SourceTypeFormModal from '@/components/settings/SourceTypeFormModal.vue'
import type { SourcePaletteEntry, SourceTypeRow } from '@/types/api'

const palette: SourcePaletteEntry[] = [
  { color: '#067647', soft: '#E7F6EC', border: '#B7E4C7', name: '绿' },
  { color: '#175CD3', soft: '#E7F0FF', border: '#C7DBFF', name: '蓝' },
]

/** Modal 直接 teleport 到 body，测试里换成渲染两个插槽的桩，便于断言弹窗内容与页脚按钮 */
const ModalStub = defineComponent({
  name: 'Modal',
  setup(_props, { slots }) {
    return () => h('div', { class: 'modal-stub' }, [slots.default?.(), slots.footer?.()])
  },
})

function row(over: Partial<SourceTypeRow> = {}): SourceTypeRow {
  return {
    code: 'S00042',
    name: '客户投诉',
    form: 'NOTE',
    color: '#067647',
    softColor: '#E7F6EC',
    borderColor: '#B7E4C7',
    sort: 3,
    enabled: true,
    builtin: false,
    locked: false,
    usageCount: 0,
    ...over,
  }
}

function mountModal(rowProp: SourceTypeRow | null) {
  return mount(SourceTypeFormModal, {
    props: { open: true, row: rowProp, palette, nextSort: 4 },
    global: { stubs: { Modal: ModalStub } },
  })
}

function saveButton(wrapper: ReturnType<typeof mountModal>) {
  const primary = wrapper.findAll('button').filter((b) => b.classes('btn--primary'))
  expect(primary).toHaveLength(1)
  return primary[0]!
}

async function pickForm(wrapper: ReturnType<typeof mountModal>, index: number) {
  const radios = wrapper.findAll('input[type="radio"]')
  await radios[index]!.setValue(true)
}

describe('来源形态三选一（T07）', () => {
  it('三张形态卡按固定顺序渲染：自由说明 / 需绑定会议 / 需绑定项目', () => {
    const wrapper = mountModal(null)
    expect(wrapper.findAll('.form-card__title').map((n) => n.text())).toEqual([
      '自由说明',
      '需绑定会议',
      '需绑定项目',
    ])
    expect(wrapper.findAll('input[type="radio"]').map((n) => n.attributes('value'))).toEqual([
      'NOTE',
      'MEETING',
      'PROJECT',
    ])
    wrapper.unmount()
  })

  it('新增态默认自由说明，不出现改形提示，按钮为「保存」', () => {
    const wrapper = mountModal(null)
    expect(wrapper.find('.source-form__warn').exists()).toBe(false)
    expect(saveButton(wrapper).text()).toBe('保存')
    wrapper.unmount()
  })

  it('编辑态形态未变：不出现提示块，按钮为「保存」', () => {
    const wrapper = mountModal(row({ usageCount: 12 }))
    expect(wrapper.find('.source-form__warn').exists()).toBe(false)
    expect(saveButton(wrapper).text()).toBe('保存')
    wrapper.unmount()
  })

  it('usageCount = 0 的改形：不出提示、不要求勾选', async () => {
    const wrapper = mountModal(row({ usageCount: 0 }))
    await pickForm(wrapper, 2)
    expect(wrapper.find('.source-form__warn').exists()).toBe(false)
    expect(saveButton(wrapper).text()).toBe('保存')
    wrapper.unmount()
  })

  it('NOTE → PROJECT 且已有任务：提示含数量与「未指定项目」，未勾不可保存', async () => {
    const wrapper = mountModal(row({ usageCount: 12 }))
    await pickForm(wrapper, 2)

    const warn = wrapper.find('.source-form__warn')
    expect(warn.exists()).toBe(true)
    expect(warn.text()).toContain('12 条历史任务')
    expect(warn.text()).toContain('未指定项目')
    expect(warn.text()).toContain('不迁移历史数据')

    const button = saveButton(wrapper)
    expect(button.text()).toBe('确认改形并保存')
    expect(button.attributes('disabled')).toBeDefined()

    await wrapper.find('.source-form__ack-input').setValue(true)
    expect(saveButton(wrapper).attributes('disabled')).toBeUndefined()

    wrapper.unmount()
  })

  it('改回原形态：提示与勾选一起消失，按钮恢复「保存」', async () => {
    const wrapper = mountModal(row({ usageCount: 12 }))
    await pickForm(wrapper, 2)
    expect(wrapper.find('.source-form__warn').exists()).toBe(true)

    await pickForm(wrapper, 0)
    expect(wrapper.find('.source-form__warn').exists()).toBe(false)
    expect(saveButton(wrapper).text()).toBe('保存')
    wrapper.unmount()
  })

  it('PROJECT → NOTE：文案说明项目照旧展示，不会转成说明文字', async () => {
    const wrapper = mountModal(row({ form: 'PROJECT', usageCount: 5 }))
    await pickForm(wrapper, 0)
    const text = wrapper.find('.source-form__warn').text()
    expect(text).toContain('5 条历史任务')
    expect(text).toContain('不会转成说明文字')
    expect(text).not.toContain('未指定项目')
    wrapper.unmount()
  })

  it('再次改形会作废旧勾选（确认只对上一次改形有效）', async () => {
    const wrapper = mountModal(row({ usageCount: 12 }))
    await pickForm(wrapper, 2)
    await wrapper.find('.source-form__ack-input').setValue(true)
    expect(saveButton(wrapper).attributes('disabled')).toBeUndefined()

    await pickForm(wrapper, 1)
    expect((wrapper.find('.source-form__ack-input').element as HTMLInputElement).checked).toBe(false)
    expect(saveButton(wrapper).attributes('disabled')).toBeDefined()
    wrapper.unmount()
  })

  it('未勾选时 Enter 提交也不发请求；勾选后提交带 form=PROJECT', async () => {
    const wrapper = mountModal(row({ usageCount: 12 }))
    await pickForm(wrapper, 2)
    await wrapper.find('form').trigger('submit')
    await flushPromises()
    expect(apiMocks.update).not.toHaveBeenCalled()

    await wrapper.find('.source-form__ack-input').setValue(true)
    apiMocks.update.mockResolvedValue(row({ form: 'PROJECT', usageCount: 12 }))
    await wrapper.find('form').trigger('submit')
    await flushPromises()
    expect(apiMocks.update).toHaveBeenCalledTimes(1)
    expect(apiMocks.update.mock.calls[0]![1]).toMatchObject({ form: 'PROJECT', name: '客户投诉' })
    wrapper.unmount()
  })
})

describe('来源弹窗几何（560 + 三卡并排）', () => {
  // jsdom 下 import.meta.url 不是 file: 协议，按 cwd（web/ 或仓库根）找同一份源码
  const rel = 'src/components/settings/SourceTypeFormModal.vue'
  const candidates = [resolve(process.cwd(), rel), resolve(process.cwd(), 'web', rel)]
  const sfcPath = candidates.find((p) => existsSync(p))
  const source = sfcPath ? readFileSync(sfcPath, 'utf8') : ''

  it('面板宽度为 560（Modal 三档之外的显式认领）', () => {
    expect(source).toContain('max-width: 560px')
    expect(source).toContain('.modal__panel:has(> .modal__body > .source-form)')
  })

  it('形态卡为三列等宽网格', () => {
    expect(source).toContain('grid-template-columns: repeat(3, minmax(0, 1fr))')
  })
})
