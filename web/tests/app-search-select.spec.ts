// @vitest-environment jsdom
/**
 * AppSearchSelect 组件测试（r4 T05）：组合框角色、过滤、键盘、停用项与空/载入态。
 * 唯一需要 jsdom 的前端单测；业务投影仍由 project-options.spec.ts 在 node 下覆盖。
 */
import { describe, expect, it } from 'vitest'
import { mount } from '@vue/test-utils'
import AppSearchSelect from '@/components/ui/AppSearchSelect.vue'

const OPTIONS = [
  { label: '全部项目', value: '' },
  { label: '轨道交通项目', value: '1' },
  { label: '信号改造项目', value: '2' },
  { label: '停用项目（已停用）', value: '3', disabled: true },
  { label: '未指定项目', value: 'none' },
]

function mountSelect(modelValue = '') {
  return mount(AppSearchSelect, {
    attachTo: document.body,
    props: { modelValue, options: OPTIONS },
    attrs: { 'aria-label': '项目筛选' },
  })
}

async function open(wrapper: ReturnType<typeof mountSelect>) {
  await wrapper.find('.field__control').trigger('mousedown')
  await new Promise((resolve) => setTimeout(resolve, 0))
}

describe('AppSearchSelect', () => {
  it('触发器的 ARIA 与搜索框角色正确，aria-label 落在输入框上', async () => {
    const wrapper = mountSelect('1')
    const input = wrapper.find('input')
    expect(input.attributes('role')).toBe('combobox')
    expect(input.attributes('aria-label')).toBe('项目筛选')
    expect(input.attributes('aria-expanded')).toBe('false')
    expect((input.element as HTMLInputElement).value).toBe('轨道交通项目')
    expect((input.element as HTMLInputElement).readOnly).toBe(true)
    wrapper.unmount()
  })

  it('打开后列出全部选项、计数排除占位与未指定、已选项打勾', async () => {
    const wrapper = mountSelect('1')
    await open(wrapper)
    expect(wrapper.findAll('[role="option"]')).toHaveLength(5)
    expect(wrapper.find('.option.is-selected').text()).toContain('轨道交通项目')
    expect(wrapper.find('.panel__footer').text()).toBe('共 3 个项目 · 显示 3 个')
    wrapper.unmount()
  })

  it('输入即过滤（大小写与空白不敏感），无匹配给出空态与清除搜索', async () => {
    const wrapper = mountSelect()
    await open(wrapper)
    const input = wrapper.find('input')
    await input.setValue('  信号 ')
    expect(wrapper.findAll('[role="option"]')).toHaveLength(1)
    await input.setValue('不存在')
    expect(wrapper.find('.empty').text()).toContain('没有匹配的项目')
    expect(wrapper.find('.empty__clear').exists()).toBe(true)
    await wrapper.find('.empty__clear').trigger('click')
    expect(wrapper.findAll('[role="option"]')).toHaveLength(5)
    wrapper.unmount()
  })

  it('↓ 跳过停用项、Enter 提交并 emit，焦点留在输入框', async () => {
    const wrapper = mountSelect()
    await open(wrapper)
    const input = wrapper.find('input')
    await input.trigger('keydown', { key: 'End' })
    // End 落到最后一档「未指定项目」，再上移一格应跳过停用项到「信号改造项目」
    await input.trigger('keydown', { key: 'ArrowUp' })
    expect(wrapper.find('.option.is-active').text()).toContain('信号改造项目')
    await input.trigger('keydown', { key: 'Enter' })
    expect(wrapper.emitted('update:modelValue')?.[0]).toEqual(['2'])
    expect(wrapper.emitted('change')?.[0]).toEqual(['2'])
    expect(wrapper.find('.panel').exists()).toBe(false)
    wrapper.unmount()
  })

  it('Esc 关闭且不改值；Tab 未导航时不提交', async () => {
    const wrapper = mountSelect('1')
    await open(wrapper)
    await wrapper.find('input').trigger('keydown', { key: 'Escape' })
    expect(wrapper.find('.panel').exists()).toBe(false)
    expect(wrapper.emitted('update:modelValue')).toBeUndefined()

    await open(wrapper)
    await wrapper.find('input').trigger('keydown', { key: 'Tab' })
    expect(wrapper.find('.panel').exists()).toBe(false)
    expect(wrapper.emitted('update:modelValue')).toBeUndefined()
    wrapper.unmount()
  })

  it('方向键导航后 Tab 提交当前高亮', async () => {
    const wrapper = mountSelect()
    await open(wrapper)
    const input = wrapper.find('input')
    await input.trigger('keydown', { key: 'ArrowDown' })
    await input.trigger('keydown', { key: 'Tab' })
    expect(wrapper.emitted('update:modelValue')?.[0]).toEqual(['1'])
    wrapper.unmount()
  })

  it('点击选项提交；停用项不提交', async () => {
    const wrapper = mountSelect()
    await open(wrapper)
    await wrapper.findAll('[role="option"]')[3].trigger('click')
    expect(wrapper.emitted('update:modelValue')).toBeUndefined()
    await wrapper.findAll('[role="option"]')[2].trigger('click')
    expect(wrapper.emitted('update:modelValue')?.[0]).toEqual(['2'])
    wrapper.unmount()
  })

  it('点击外部关闭', async () => {
    const wrapper = mountSelect()
    await open(wrapper)
    expect(wrapper.find('.panel').exists()).toBe(true)
    document.body.dispatchEvent(new MouseEvent('mousedown', { bubbles: true }))
    await new Promise((resolve) => setTimeout(resolve, 0))
    expect(wrapper.find('.panel').exists()).toBe(false)
    wrapper.unmount()
  })

  it('加载中显示骨架而不显示空态', async () => {
    const wrapper = mount(AppSearchSelect, {
      attachTo: document.body,
      props: { modelValue: '', options: [], loading: true },
    })
    await open(wrapper)
    expect(wrapper.findAll('.skeleton')).toHaveLength(3)
    expect(wrapper.find('.empty').exists()).toBe(false)
    wrapper.unmount()
  })
})
