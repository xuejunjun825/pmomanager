// @vitest-environment jsdom
/**
 * 来源标签契约（DESIGN §1.3 / AC-5）：
 * - 名称与三色只认接口下发的值，组件不按 code 分支；
 * - 名称缺失兜底「未知来源（CODE）」，颜色缺失回落 slate —— 页面不允许出现空白标签；
 * - 20 高 / 11px / 左右 6px，颜色写在行内 style 上（新增来源类型零改样式表）。
 */
import { describe, expect, it } from 'vitest'
import { mount } from '@vue/test-utils'
import SourceTag from '@/components/ui/SourceTag.vue'

describe('SourceTag', () => {
  it('用接口下发的名称与三色渲染，不按 code 分支', () => {
    const wrapper = mount(SourceTag, {
      props: {
        code: 'S00042',
        name: '客户回访',
        color: '#0b7a6e',
        softColor: '#f0fdfa',
        borderColor: '#99f6e4',
      },
    })
    expect(wrapper.text()).toBe('客户回访')
    const style = (wrapper.element as HTMLElement).style
    expect(style.color).toBe('rgb(11, 122, 110)')
    expect(style.backgroundColor).toBe('rgb(240, 253, 250)')
    expect(style.borderColor).toBe('rgb(153, 246, 228)')
    expect(wrapper.classes()).not.toContain('tag--meeting')
  })

  it('名称缺失时兜底「未知来源（CODE）」并回落 slate', () => {
    const wrapper = mount(SourceTag, { props: { code: 'S99999' } })
    expect(wrapper.text()).toBe('未知来源（S99999）')
    const style = (wrapper.element as HTMLElement).style
    expect(style.color).toBe('var(--src-slate-text)')
    expect(style.backgroundColor).toBe('var(--src-slate-soft)')
    expect(style.borderColor).toBe('var(--src-slate-border)')
  })

  it('空名称（纯空白）同样走兜底，不渲染空壳', () => {
    const wrapper = mount(SourceTag, { props: { code: 'MEETING', name: '  ' } })
    expect(wrapper.text()).toBe('未知来源（MEETING）')
  })

  it('title 带会议名，便于悬停看到来源上下文', () => {
    const wrapper = mount(SourceTag, {
      props: { code: 'MEETING', name: '会议决议', meetingTitle: '项目周会' },
    })
    expect(wrapper.attributes('title')).toBe('会议决议：项目周会')
  })
})
