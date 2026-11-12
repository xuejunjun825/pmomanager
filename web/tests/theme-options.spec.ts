// @vitest-environment node
/**
 * `themeOptions` / `themeView` 的唯一投影（r7 T5）。
 *
 * 钉住三件事：停用项仍在下拉里并带后缀（历史可筛）、未挂主题用 `none` 哨兵且排在最后、
 * 主题展示兜底对「无主题」返回 unspecified（页面据此不渲染空标签）。
 */
import { describe, expect, it } from 'vitest'
import { UNASSIGNED_VALUE } from '@/utils/projectOptions'
import { themeOptions, themeView } from '@/utils/themeOptions'
import type { ThemeRef, ThemeRow } from '@/types/api'

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

describe('themeOptions', () => {
  it('默认按启用→sort→id 排序，停用项在后并带「（已停用）」后缀，值恒为字符串 id', () => {
    const list = [
      row({ id: 3, name: '老旧', sort: 1, enabled: false }),
      row({ id: 2, name: '交付', sort: 2 }),
      row({ id: 1, name: '安全', sort: 2 }),
      row({ id: 4, name: '未启用', sort: 5, enabled: false }),
    ]
    const options = themeOptions(list, { allOption: false })
    expect(options.map((item) => item.value)).toEqual(['1', '2', '3', '4'])
    expect(options.map((item) => item.label)).toEqual([
      '安全',
      '交付',
      '老旧（已停用）',
      '未启用（已停用）',
    ])
    // 默认不产生「全部主题」——本用例已显式关闭
    expect(options.some((item) => item.value === '')).toBe(false)
  })

  it('allOption 首插「全部主题」；includeUnassigned 尾插「未挂主题」，哨兵与项目一致', () => {
    const options = themeOptions([row({ id: 7, name: '安全' })], {
      allOption: true,
      includeUnassigned: true,
    })
    expect(options[0]).toEqual({ label: '全部主题', value: '' })
    expect(options[1].value).toBe('7')
    expect(options[options.length - 1]).toEqual({ label: '未挂主题', value: UNASSIGNED_VALUE })
    expect(UNASSIGNED_VALUE).toBe('none')
  })

  it('includeDisabled=false 时剔除停用项（只允许用于新建下拉）', () => {
    const options = themeOptions(
      [row({ id: 1, enabled: false }), row({ id: 2, name: '交付' })],
      { allOption: false, includeDisabled: false },
    )
    expect(options.map((item) => item.value)).toEqual(['2'])
  })

  it('不修改入参数组（投影只读）', () => {
    const list = [row({ id: 2, sort: 2 }), row({ id: 1, sort: 1 })]
    const before = list.map((item) => item.id)
    themeOptions(list)
    expect(list.map((item) => item.id)).toEqual(before)
  })
})

describe('themeView', () => {
  const theme: ThemeRef = {
    id: 9,
    name: '交付',
    color: '#067647',
    softColor: '#ecfdf3',
    borderColor: '#abefc6',
    enabled: true,
  }

  it('完整主题原样透出三色', () => {
    expect(themeView(theme)).toEqual({
      name: '交付',
      color: '#067647',
      softColor: '#ecfdf3',
      borderColor: '#abefc6',
      enabled: true,
      unspecified: false,
    })
  })

  it('停用主题保留三色并标记 enabled=false（由 ThemeTag 加后缀）', () => {
    const view = themeView({ ...theme, enabled: false })
    expect(view.enabled).toBe(false)
    expect(view.unspecified).toBe(false)
    expect(view.name).toBe('交付')
  })

  it('null / undefined / 空名称一律 unspecified，且不编造颜色', () => {
    for (const value of [null, undefined, { ...theme, name: '  ' }]) {
      const view = themeView(value)
      expect(view.unspecified).toBe(true)
      expect(view.name).toBe('')
      expect(view.color).toBeNull()
      expect(view.softColor).toBeNull()
      expect(view.borderColor).toBeNull()
    }
  })
})
