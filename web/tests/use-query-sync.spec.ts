/**
 * 筛选条件 ↔ URL 往返（TECHNICAL §6.3 / AC-8）。
 * 纯函数直接钉住三件事：类型解码、非法值回落、与默认值相同的键不写进 URL。
 */
import { describe, expect, it } from 'vitest'
import { parseQuery, toQuery } from '@/composables/useQuerySync'

interface Demo {
  status: string
  owner: number | ''
  overdue: boolean | ''
  q: string
  page: number
}

const DEFAULTS: Demo = { status: '', owner: '', overdue: '', q: '', page: 1 }
const KINDS = { owner: 'number', overdue: 'boolean', page: 'number' } as const

describe('parseQuery', () => {
  it('把 URL 字符串解码成强类型状态', () => {
    const state = parseQuery(DEFAULTS, { status: 'PENDING', owner: '7', overdue: 'true', q: '验收', page: '3' }, KINDS)
    expect(state).toEqual({ status: 'PENDING', owner: 7, overdue: true, q: '验收', page: 3 })
  })

  it('空串与缺失键都落回默认值', () => {
    expect(parseQuery(DEFAULTS, {}, KINDS)).toEqual(DEFAULTS)
    expect(parseQuery(DEFAULTS, { status: '', owner: '' }, KINDS)).toEqual(DEFAULTS)
  })

  it('非法值不抛异常，回落默认值（手改 URL 不应白屏）', () => {
    const state = parseQuery(DEFAULTS, { owner: 'abc', page: 'x', overdue: 'maybe' }, KINDS)
    expect(state.owner).toBe('')
    expect(state.page).toBe(1)
    expect(state.overdue).toBe('')
  })

  it('数组形式（重复参数）取第一个值', () => {
    expect(parseQuery(DEFAULTS, { owner: ['7', '9'] }, KINDS).owner).toBe(7)
  })
})

describe('toQuery', () => {
  it('只写生效中的条件：与默认值相同的键不进 URL', () => {
    expect(toQuery(DEFAULTS, DEFAULTS)).toEqual({})
    const state: Demo = { status: 'PENDING', owner: 7, overdue: '', q: '', page: 2 }
    expect(toQuery(state, DEFAULTS)).toEqual({ status: 'PENDING', owner: '7', page: '2' })
  })

  it('往返后状态不变', () => {
    const state: Demo = { status: 'IN_PROGRESS', owner: 12, overdue: false, q: '报告', page: 4 }
    const query = toQuery(state, DEFAULTS)
    expect(parseQuery(DEFAULTS, query, KINDS)).toEqual(state)
  })

  it('布尔 false 是可分享的有效条件，不能被当成空值丢掉', () => {
    const state: Demo = { ...DEFAULTS, overdue: false }
    expect(toQuery(state, DEFAULTS)).toEqual({ overdue: 'false' })
    expect(parseQuery(DEFAULTS, { overdue: 'false' }, KINDS).overdue).toBe(false)
  })
})
