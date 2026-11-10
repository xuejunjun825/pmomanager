/**
 * sourceOptions util 单测（r3，纯 node 环境，无需 jsdom）。
 *
 * 把 R-1 的回归钉在纯函数上：占位、启用优先/停用后置、停用标注、仅启用变体，
 * 以及「绝不就地排序入参」这条无副作用约束。
 */
import { describe, expect, it } from 'vitest'
import { sourceOptions } from '@/utils/sourceOptions'
import type { SourceTypeOption } from '@/types/api'

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

/** 乱序输入：停用项在最前，启用项在后，用来同时验证排序与「不改入参」 */
const CONFIG: SourceTypeOption[] = [
  option({ value: 'ARCHIVE', label: '历史专项', enabled: false, sort: 4, form: 'NOTE' }),
  option({ value: 'PROJECT', label: '项目任务', sort: 2, form: 'NOTE' }),
  option({ value: 'REVIEW', label: '复盘事项', enabled: false, sort: 3, form: 'NOTE' }),
  option({ value: 'MEETING', label: '会议决议', sort: 1 }),
]

const ALL_OPTION = { label: '全部来源', value: '' }

describe('sourceOptions', () => {
  it('undefined / null / 空数组只返回占位项', () => {
    expect(sourceOptions(undefined)).toEqual([ALL_OPTION])
    expect(sourceOptions(null)).toEqual([ALL_OPTION])
    expect(sourceOptions([])).toEqual([ALL_OPTION])
  })

  it('默认：占位在首位，启用按 sort 在前，停用按 sort 在后并标注「（已停用）」', () => {
    expect(sourceOptions(CONFIG)).toEqual([
      ALL_OPTION,
      { label: '会议决议', value: 'MEETING' },
      { label: '项目任务', value: 'PROJECT' },
      { label: '复盘事项（已停用）', value: 'REVIEW' },
      { label: '历史专项（已停用）', value: 'ARCHIVE' },
    ])
  })

  it('allOption:false 不插占位，排序与停用标注不变', () => {
    expect(sourceOptions(CONFIG, { allOption: false })).toEqual([
      { label: '会议决议', value: 'MEETING' },
      { label: '项目任务', value: 'PROJECT' },
      { label: '复盘事项（已停用）', value: 'REVIEW' },
      { label: '历史专项（已停用）', value: 'ARCHIVE' },
    ])
  })

  it('includeDisabled:false 只留启用项且无占位（建任务抽屉变体）', () => {
    expect(sourceOptions(CONFIG, { allOption: false, includeDisabled: false })).toEqual([
      { label: '会议决议', value: 'MEETING' },
      { label: '项目任务', value: 'PROJECT' },
    ])
    // 单独关停用、占位仍默认保留
    expect(sourceOptions(CONFIG, { includeDisabled: false })).toEqual([
      ALL_OPTION,
      { label: '会议决议', value: 'MEETING' },
      { label: '项目任务', value: 'PROJECT' },
    ])
  })

  it('不就地排序入参，也不改对象引用与 label', () => {
    const snapshot = [...CONFIG]
    const labels = CONFIG.map((item) => item.label)
    const refs = CONFIG.map((item) => item)

    sourceOptions(CONFIG)
    sourceOptions(CONFIG, { includeDisabled: false })

    expect(CONFIG).toEqual(snapshot)
    expect(CONFIG.map((item) => item.label)).toEqual(labels)
    expect(CONFIG.map((item) => item)).toEqual(refs)
    refs.forEach((item, index) => expect(CONFIG[index]).toBe(item))
  })
})
