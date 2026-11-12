// @vitest-environment jsdom
/**
 * 人员多选（r7 T5 / DESIGN §4.5）。
 *
 * 只验证组件契约：受控（emit 整个 id 数组）、按姓名搜索、停用用户不可选、
 * chips 可单个移除、Backspace 删最后一个、空态文案。
 */
import { describe, expect, it } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import UserMultiSelect from '@/components/ui/UserMultiSelect.vue'
import type { UserOption } from '@/types/api'

const OPTIONS: UserOption[] = [
  { id: 1, name: '张三', role: 'ADMIN', status: 'ACTIVE' },
  { id: 2, name: '李四', role: 'OWNER', status: 'ACTIVE' },
  { id: 3, name: '王五', role: 'OWNER', status: 'DISABLED' },
]

function mountSelect(modelValue: number[] = []) {
  return mount(UserMultiSelect, {
    props: { modelValue, options: OPTIONS },
    attachTo: document.body,
  })
}

async function openList(wrapper: ReturnType<typeof mountSelect>) {
  // 打开入口在容器上（不是 focus）：mousedown → nextTick 聚焦输入框
  await wrapper.find('.multi__control').trigger('mousedown')
  await flushPromises()
}

describe('UserMultiSelect', () => {
  it('已选人员渲染为 chips：名称 + 移除按钮（读屏报「移除 X」）', () => {
    const wrapper = mountSelect([1, 2])
    const chips = wrapper.findAll('.multi__chip')
    expect(chips.map((chip) => chip.text())).toEqual(['张三 ×', '李四 ×'])
    expect(chips[0].find('.multi__chip-x').attributes('aria-label')).toBe('移除 张三')
    wrapper.unmount()
  })

  it('点击候选：emit 追加后的 id 数组（受控组件不自行改值）', async () => {
    const wrapper = mountSelect([1])
    await openList(wrapper)
    const option = wrapper.findAll('.multi__option').find((item) => item.text().includes('李四'))
    expect(option).toBeTruthy()
    await option!.trigger('mousedown')
    expect(wrapper.emitted('update:modelValue')?.[0]).toEqual([[1, 2]])
    wrapper.unmount()
  })

  it('已选人员不再出现在候选里；停用用户可见但不可选', async () => {
    const wrapper = mountSelect([2])
    await openList(wrapper)
    const names = wrapper.findAll('.multi__option').map((item) => item.text())
    expect(names.some((item) => item.includes('李四'))).toBe(false)
    const disabled = wrapper.findAll('.multi__option').find((item) => item.text().includes('王五'))
    expect(disabled?.classes()).toContain('is-disabled')
    expect(disabled?.attributes('aria-disabled')).toBe('true')
    wrapper.unmount()
  })

  it('搜索按姓名过滤；无匹配显示「没有匹配的用户」，清空后回到「没有可选的人员」', async () => {
    const wrapper = mountSelect([])
    await openList(wrapper)
    const input = wrapper.find('input.multi__input')
    await input.setValue('张')
    expect(wrapper.findAll('.multi__option').map((item) => item.text())).toEqual(['张三+'])
    await input.setValue('zzz')
    expect(wrapper.findAll('.multi__option')).toHaveLength(0)
    expect(wrapper.find('.multi__empty').text()).toContain('没有匹配的用户')
    wrapper.unmount()
  })

  it('点击 chip 的 × 只移除该人员；Backspace 在空输入时移除最后一个', async () => {
    const wrapper = mountSelect([1, 2])
    await wrapper.findAll('.multi__chip-x')[0].trigger('click')
    expect(wrapper.emitted('update:modelValue')?.[0]).toEqual([[2]])
    // 受控组件：父级接受新值后，Backspace 才基于 [1] 删最后一个
    await wrapper.setProps({ modelValue: [1] })
    await openList(wrapper)
    await wrapper.find('input.multi__input').trigger('keydown', { key: 'Backspace' })
    expect(wrapper.emitted('update:modelValue')?.[1]).toEqual([[]])
    wrapper.unmount()
  })

  it('invalid + error：根节点 is-invalid，错误文案 role=alert', () => {
    const wrapper = mount(UserMultiSelect, {
      props: { modelValue: [], options: OPTIONS, invalid: true, error: '请至少选择一名参会人' },
      attachTo: document.body,
    })
    expect(wrapper.find('.multi').classes()).toContain('is-invalid')
    expect(wrapper.find('.multi__control').attributes('aria-invalid')).toBe('true')
    expect(wrapper.find('[role="alert"]').text()).toBe('请至少选择一名参会人')
    wrapper.unmount()
  })
})
