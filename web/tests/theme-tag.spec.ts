// @vitest-environment jsdom
/**
 * 主题标签（r7 T5 / DESIGN §3.10、§4.3）。
 *
 * 只断言组件契约：三色只来自 props、缺色回落 slate、空名渲染「—」而不产生空标签、
 * 停用用文字后缀而不是降透明度（对比度不能靠 opacity 掉）。
 */
import { describe, expect, it } from 'vitest'
import { mount } from '@vue/test-utils'
import ThemeTag from '@/components/ui/ThemeTag.vue'

const COLORS = {
  color: '#b42318',
  softColor: '#fef3f2',
  borderColor: '#fecdca',
}

function mountTag(props: Record<string, unknown> = {}) {
  return mount(ThemeTag, { props: { name: '安全', ...COLORS, ...props } })
}

describe('ThemeTag', () => {
  it('名称与三色都来自 props：内联样式逐字使用，不派生第二套色值', () => {
    const wrapper = mountTag()
    expect(wrapper.text()).toContain('安全')
    // jsdom 会把 hex 归一化成 rgb，断言的是「同一个颜色值原样落到内联样式」
    const element = wrapper.element as HTMLElement
    expect(element.style.color).toBe('rgb(180, 35, 24)')
    expect(element.style.backgroundColor).toBe('rgb(254, 243, 242)')
    expect(element.style.borderColor).toBe('rgb(254, 205, 202)')
    expect(wrapper.attributes('title')).toBe('安全')
    expect((wrapper.find('.theme-tag__dot').element as HTMLElement).style.backgroundColor).toBe(
      'rgb(180, 35, 24)',
    )
  })

  it('停用主题：文字后缀「（已停用）」，标签仍保留原三色', () => {
    const wrapper = mountTag({ enabled: false })
    expect(wrapper.text()).toBe('安全（已停用）')
    expect(wrapper.classes()).toContain('theme-tag--disabled')
    expect((wrapper.element as HTMLElement).style.color).toBe('rgb(180, 35, 24)')
  })

  it('没有名称：渲染「—」占位，不显示圆点、不套主题色（页面据此本就不渲染）', () => {
    const wrapper = mountTag({ name: '' })
    expect(wrapper.text()).toBe('—')
    expect(wrapper.classes()).toContain('theme-tag--empty')
    expect(wrapper.find('.theme-tag__dot').exists()).toBe(false)
    expect(wrapper.attributes('title')).toBeUndefined()
  })

  it('缺色时回落 slate 令牌：不产生非法 css、不借用其它主题色', () => {
    const wrapper = mount(ThemeTag, { props: { name: '交付' } })
    const style = wrapper.attributes('style') ?? ''
    expect(style).toContain('--src-slate-text')
    expect(style).toContain('--src-slate-soft')
    expect(style).toContain('--src-slate-border')
  })})
