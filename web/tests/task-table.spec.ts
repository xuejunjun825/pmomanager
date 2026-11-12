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
  it('数据列宽合计 1036px，加 24px 箭头列 = 1060px（10 个格子）', () => {
    const wrapper = mount(TaskTable, { props: { items: [row()] } })
    const headers = wrapper.findAll('thead th')
    expect(headers).toHaveLength(9)
    const total = headers.reduce(
      (sum, th) => sum + Number.parseFloat((th.element as HTMLElement).style.width || '0'),
      0,
    )
    expect(total).toBe(1036)
    // 箭头列只出现在行尾（DataTable 的 clickable 约定），9 + 1 = 10
    expect(wrapper.findAll('tbody tr:first-child td')).toHaveLength(10)
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

  /**
   * r4 / T08：来源列第二行按形态分流（DESIGN §3.8）。
   * 有项目时不再渲染说明文字；项目型但无项目 = 改形历史，显示「未指定项目」占位。
   */
  it('项目型来源：第二行是项目标签（名称 + 停用后缀），不再是会议名 / 说明', () => {
    const wrapper = mount(TaskTable, {
      props: {
        items: [
          row({
            source: {
              type: 'S20001',
              typeLabel: '客户投诉',
              form: 'PROJECT',
              projectId: 12,
              projectName: '老旧小区改造',
              projectEnabled: true,
            },
          }),
          row({
            id: 2,
            source: {
              type: 'S20001',
              typeLabel: '客户投诉',
              form: 'PROJECT',
              projectId: 9,
              projectName: '停用项目',
              projectEnabled: false,
            },
          }),
        ],
      },
    })

    const firstRow = wrapper.findAll('tbody tr')[0]!
    expect(firstRow.text()).toContain('老旧小区改造')
    // 项目标签带 128px 截断与 title 全名（只显示色块或编码都算不合格）
    const tag = firstRow.find('.cell-source__project')
    expect(tag.exists()).toBe(true)
    expect(tag.attributes('title')).toBe('老旧小区改造')
    expect(wrapper.findAll('tbody tr')[1]!.find('.cell-source__project').attributes('title')).toBe(
      '停用项目（已停用）',
    )
  })

  it('改形历史（形态是项目型但没项目）：显示「未指定项目」占位', () => {
    const wrapper = mount(TaskTable, {
      props: {
        items: [
          row({
            source: { type: 'S20001', typeLabel: '客户投诉', form: 'PROJECT', projectId: null },
          }),
        ],
      },
    })
    const cell = wrapper.findAll('tbody tr')[0]!.findAll('td')[3]!
    expect(cell.text()).toContain('未指定项目')
  })

  it('会议 / 说明型来源不渲染任何项目元素（不留空位、不显示「—」）', () => {
    const wrapper = mount(TaskTable, {
      props: {
        items: [
          row({ source: { type: 'MEETING', meetingId: 2, meetingTitle: '项目周会' } }),
          row({ id: 3, source: { type: 'NOTE_TYPE', form: 'NOTE', note: '电话转来的投诉' } }),
        ],
      },
    })
    expect(wrapper.findAll('.cell-source__project')).toHaveLength(0)
    expect(wrapper.text()).toContain('项目周会')
    expect(wrapper.text()).toContain('电话转来的投诉')
  })
})
