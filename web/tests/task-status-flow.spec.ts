// @vitest-environment jsdom
/**
 * 五步闭环条（r7 T6 / DESIGN §3.6）。
 *
 * 状态机仍是 PENDING→IN_PROGRESS→PENDING_ACCEPT→DONE（+CANCELLED），而步骤有 5 个：
 * 「提交」在 PENDING_ACCEPT 时算已完成。测试钉住的正是这个映射，避免页面再手写一套 4 步。
 */
import { describe, expect, it } from 'vitest'
import { mount } from '@vue/test-utils'
import TaskStatusFlow from '@/components/task/TaskStatusFlow.vue'
import type { TaskStatus } from '@/types/enums'

function mountFlow(status: TaskStatus, extra: Record<string, unknown> = {}) {
  return mount(TaskStatusFlow, { props: { status, ownerName: '张三', dispatcherName: '李四', ...extra } })
}

const STATES = (wrapper: ReturnType<typeof mountFlow>) =>
  wrapper.findAll('.flow__step').map((item) => {
    const classes = item.classes()
    if (classes.includes('is-current')) return 'current'
    if (classes.includes('is-done')) return 'done'
    return 'future'
  })

describe('TaskStatusFlow', () => {
  it('五步标签固定为 派发/进行/提交/验收/完成', () => {
    const wrapper = mountFlow('PENDING')
    expect(wrapper.findAll('.flow__label').map((item) => item.text())).toEqual([
      '派发',
      '进行',
      '提交',
      '验收',
      '完成',
    ])
    expect(wrapper.findAll('.flow__step')).toHaveLength(5)
  })

  it.each([
    ['PENDING', ['current', 'future', 'future', 'future', 'future'], '负责人 张三「开始处理」'],
    ['IN_PROGRESS', ['done', 'current', 'future', 'future', 'future'], '负责人 张三「提交交付」'],
    ['PENDING_ACCEPT', ['done', 'done', 'done', 'current', 'future'], '派发人 李四「验收通过 / 打回」'],
    ['DONE', ['done', 'done', 'done', 'done', 'current'], '已闭环，无待办操作'],
  ] as const)('%s：步骤状态与「下一步」文案', (status, expected, next) => {
    const wrapper = mountFlow(status)
    expect(STATES(wrapper)).toEqual([...expected])
    expect(wrapper.find('.flow__next').text()).toContain(next)
  })

  it('CANCELLED：整条灰化、无当前步、右侧「已取消」，下一步说明无待办', () => {
    const wrapper = mountFlow('CANCELLED')
    expect(STATES(wrapper)).toEqual(['future', 'future', 'future', 'future', 'future'])
    expect(wrapper.text()).toContain('已取消')
    expect(wrapper.find('.flow__next').text()).toContain('任务已取消，无待办操作')
  })

  it('人名缺失退化为角色词，不出现 undefined', () => {
    const wrapper = mount(TaskStatusFlow, { props: { status: 'PENDING' } })
    expect(wrapper.find('.flow__next').text()).toBe('下一步：负责人 负责人「开始处理」')
    expect(wrapper.text()).not.toContain('undefined')
  })

  it('读屏整句只报已完成与当前，不逐个念未来步骤', () => {
    const wrapper = mountFlow('PENDING_ACCEPT')
    const label = wrapper.find('.flow__track').attributes('aria-label') ?? ''
    expect(label).toBe('任务进度：已完成派发、进行、提交，当前验收')
  })
})
