/**
 * projectOptions / projectView 单测（r4 T05，纯 node 环境，无需 jsdom）。
 *
 * 把「唯一投影」与「唯一展示判定」钉在纯函数上：
 * 占位与「未指定项目」的出现条件、启用优先/停用后置、停用后缀、绝不就地排序、
 * 选项不带编码，以及 projectView 的三分支（有项目 / 改形历史 / 不渲染）。
 */
import { describe, expect, it } from 'vitest'
import {
  DISABLED_SUFFIX,
  UNASSIGNED_VALUE,
  projectOptions,
  projectView,
} from '@/utils/projectOptions'
import type { ProjectOptionRow, TaskSource } from '@/types/api'

function project(over: Partial<ProjectOptionRow>): ProjectOptionRow {
  return { id: 1, name: '项目一', enabled: true, sort: 1, ...over }
}

/** 乱序输入：停用项在前、sort 与人眼顺序相反，用来同时验证排序与「不改入参」 */
const CONFIG: ProjectOptionRow[] = [
  project({ id: 9, name: '停用项目', enabled: false, sort: 1 }),
  project({ id: 3, name: '项目丙', sort: 30 }),
  project({ id: 2, name: '项目乙', sort: 20 }),
  project({ id: 4, name: '项目丁', sort: 20 }),
  project({ id: 1, name: '项目甲', sort: 10 }),
]

const ALL_OPTION = { label: '全部项目', value: '' }
const NONE_OPTION = { label: '未指定项目', value: UNASSIGNED_VALUE }

describe('projectOptions', () => {
  it('undefined / null / 空数组只返回占位项', () => {
    expect(projectOptions(undefined)).toEqual([ALL_OPTION])
    expect(projectOptions(null)).toEqual([ALL_OPTION])
    expect(projectOptions([])).toEqual([ALL_OPTION])
  })

  it('默认：全部项目在最前，启用项按 sort 升序（同 sort 按 id），停用项后置并加后缀', () => {
    expect(projectOptions(CONFIG)).toEqual([
      ALL_OPTION,
      { label: '项目甲', value: '1' },
      { label: '项目乙', value: '2' },
      { label: '项目丁', value: '4' },
      { label: '项目丙', value: '3' },
      { label: `停用项目${DISABLED_SUFFIX}`, value: '9' },
    ])
  })

  it('绝不就地排序入参（三页共用同一份 meta 数组）', () => {
    const snapshot = CONFIG.map((item) => item.id)
    projectOptions(CONFIG)
    projectOptions(CONFIG, { includeUnassigned: true, includeDisabled: false })
    expect(CONFIG.map((item) => item.id)).toEqual(snapshot)
  })

  it('includeDisabled: false 只留启用项（建任务抽屉）', () => {
    const options = projectOptions(CONFIG, { includeDisabled: false, allOption: false })
    expect(options.map((item) => item.value)).toEqual(['1', '2', '4', '3'])
  })

  it('allOption: false 时没有「全部项目」，但「未指定项目」仍在末尾', () => {
    const options = projectOptions(CONFIG, { allOption: false, includeUnassigned: true })
    expect(options[0]).toEqual({ label: '项目甲', value: '1' })
    expect(options[options.length - 1]).toEqual(NONE_OPTION)
  })

  it('「未指定项目」只在 includeUnassigned 时出现，且是独立一档', () => {
    const without = projectOptions(CONFIG)
    expect(without.some((item) => item.value === UNASSIGNED_VALUE)).toBe(false)
    const withUnassigned = projectOptions(CONFIG, { includeUnassigned: true })
    expect(withUnassigned[withUnassigned.length - 1]).toEqual(NONE_OPTION)
    expect(withUnassigned.filter((item) => item.value === UNASSIGNED_VALUE)).toHaveLength(1)
  })

  it('选项只含名称，绝不带编码（P00012 不得出现在下拉里）', () => {
    const options = projectOptions([project({ id: 12, name: '研发项目', sort: 1, enabled: true })])
    expect(options.map((item) => item.label)).toEqual(['全部项目', '研发项目'])
    expect(options.some((item) => item.label.includes('P00012') || item.value === 'P00012')).toBe(false)
  })

  it('enabled 缺失（老接口）按启用处理，不加后缀', () => {
    const legacy = { id: 5, name: '历史项目', sort: 1 } as ProjectOptionRow
    expect(projectOptions([legacy], { allOption: false })).toEqual([
      { label: '历史项目', value: '5' },
    ])
  })
})

describe('projectView', () => {
  function source(over: Partial<TaskSource>): TaskSource {
    return {
      type: 'PROJECT',
      typeLabel: '项目任务',
      color: '#2563EB',
      softColor: '#EFF6FF',
      borderColor: '#BFDBFE',
      ...over,
    }
  }

  it('无来源或会议/说明型来源（含缺 form 的老接口）不渲染项目位', () => {
    expect(projectView(null)).toBeNull()
    expect(projectView(undefined)).toBeNull()
    expect(projectView(source({}))).toBeNull()
    expect(projectView(source({ form: 'NOTE' }))).toBeNull()
    expect(projectView(source({ form: 'MEETING', meetingId: 3 }))).toBeNull()
  })

  it('有项目：名称完整保留，projectEnabled 为 false 才算停用', () => {
    const view = projectView(
      source({ form: 'PROJECT', projectId: 7, projectName: '轨道交通信号改造项目二期', projectEnabled: true }),
    )
    expect(view).toEqual({ name: '轨道交通信号改造项目二期', enabled: true, unspecified: false })

    expect(
      projectView(source({ form: 'PROJECT', projectId: 7, projectName: '老项目', projectEnabled: false })),
    ).toEqual({ name: '老项目', enabled: false, unspecified: false })
  })

  it('projectEnabled 为 null（未知）按启用显示，绝不误判成停用', () => {
    expect(
      projectView(source({ form: 'PROJECT', projectId: 7, projectName: '新项目', projectEnabled: null })),
    ).toEqual({ name: '新项目', enabled: true, unspecified: false })
  })

  it('改形历史：form=PROJECT 但没有项目 → 未指定项目', () => {
    expect(projectView(source({ form: 'PROJECT', projectId: null }))).toEqual({
      name: '',
      enabled: true,
      unspecified: true,
    })
    // 老接口没有 form 时不得误判为改形历史
    expect(projectView(source({ projectId: null }))).toBeNull()
  })

  it('来源是说明型但带历史项目位时，仍按有项目渲染（数据优先于形态）', () => {
    expect(
      projectView(source({ form: 'NOTE', projectId: 8, projectName: '历史绑定', projectEnabled: true })),
    ).toEqual({ name: '历史绑定', enabled: true, unspecified: false })
  })
})
