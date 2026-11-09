// @vitest-environment jsdom
/**
 * 任务池表格契约（DESIGN §4.2 / AC-8 / AC-11）：
 * - 8 个数据列 + 箭头列 = 9 列，宽度合计 964px（不横滚的前提）；
 * - 逾期只认服务端字段：截止日期已经过去但 isOverdue=false 时不许出现逾期标记。
 */
import { describe, expect, it } from 'vitest'
import { mount } from '@vue/test-utils'
import TaskTable from '@/components/task/TaskTable.vue'
import type { TaskRow } from '@/types/api'

function row(overrides: Partial<TaskRow> = {}): TaskRow {
  return {
    id: 1,
    title: '整理周会决议',
    owner: { id: 7, name: '张三' },
    dueDate: '2026-11-10',
    source: { type: 'MEETING', meetingTitle: '项目周会' },
    status: 'IN_PROGRESS',
    isOverdue: false,
    overdueDays: 0,
    dueSoon: false,
    urgeCount: 0,
    updatedAt: '2026-11-09T09:00:00+08:00',
    ...overrides,
  }
}

describe('TaskTable', () => {
  it('数据列宽合计 940px，加 24px 箭头列 = 964px（9 个格子）', () => {
    const wrapper = mount(TaskTable, { props: { items: [row()] } })
    const headers = wrapper.findAll('thead th')
    expect(headers).toHaveLength(8)
    const total = headers.reduce(
      (sum, th) => sum + Number.parseFloat((th.element as HTMLElement).style.width || '0'),
      0,
    )
    expect(total).toBe(940)
    // 箭头列只出现在行尾（DataTable 的 clickable 约定），8 + 1 = 9
    expect(wrapper.findAll('tbody tr:first-child td')).toHaveLength(9)
    expect(wrapper.find('.table__chevron').exists()).toBe(true)
  })

  it('isOverdue=true 时显示「逾期 N 天」', () => {
    const wrapper = mount(TaskTable, {
      props: { items: [row({ dueDate: '2026-11-01', isOverdue: true, overdueDays: 8 })] },
    })
    expect(wrapper.text()).toContain('逾期 8 天')
  })

  it('isOverdue=false 时即便截止日期已过也不显示逾期（不自行推算日期）', () => {
    const wrapper = mount(TaskTable, {
      props: { items: [row({ dueDate: '2020-01-01', isOverdue: false, overdueDays: 0 })] },
    })
    // 表头本来就有「逾期」一列，这里要断言的是没有任何逾期 pill
    expect(wrapper.text()).not.toMatch(/逾期 \d+ 天/)
  })

  it('空列表给出可行动的空态文案', () => {
    const wrapper = mount(TaskTable, {
      props: { items: [], emptyTitle: '没有符合条件的任务', emptyHint: '放宽筛选条件后再试' },
    })
    expect(wrapper.text()).toContain('没有符合条件的任务')
    expect(wrapper.text()).toContain('放宽筛选条件后再试')
  })
})
