<script setup lang="ts">
/**
 * 来源标签（DESIGN §1.3）：20 高 / 11px / 左右 6。
 * 颜色与名称全部由接口下发（任务行 source 三色 / meta.sourceTypes），组件内不按 code 分支；
 * 缺名称时兜底「未知来源（CODE）」，缺颜色时回落 slate 令牌，页面不会出现空白或透明标签。
 * 名称最长 10 字，超长省略并保留 title 全名（表格列宽固定，标签不得把列撑开）。
 */
import { computed } from 'vue'

const props = defineProps<{
  code: string
  name?: string | null
  color?: string | null
  softColor?: string | null
  borderColor?: string | null
  meetingTitle?: string | null
}>()

const label = computed(() => {
  const name = props.name?.trim()
  if (name) return name
  return props.code ? `未知来源（${props.code}）` : '未知来源'
})

/** 未知来源只用文字兜底，不再猜颜色：统一 slate，避免与内置三色混淆 */
const style = computed(() => ({
  color: props.color || 'var(--src-slate-text)',
  backgroundColor: props.softColor || 'var(--src-slate-soft)',
  borderColor: props.borderColor || 'var(--src-slate-border)',
}))

const title = computed(() =>
  props.meetingTitle ? `${label.value}：${props.meetingTitle}` : label.value,
)
</script>

<template>
  <span class="tag" :style="style" :title="title">
    <span class="tag__text">{{ label }}</span>
  </span>
</template>

<style scoped>
.tag {
  display: inline-flex;
  align-items: center;
  box-sizing: border-box;
  max-width: 128px;
  height: 20px;
  padding: 0 6px;
  border: 1px solid transparent;
  border-radius: var(--r-sm);
  font-size: var(--fs-micro);
  line-height: var(--lh-micro);
  font-weight: 500;
  white-space: nowrap;
  vertical-align: middle;
}
.tag__text {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
</style>
