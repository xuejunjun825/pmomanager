<script setup lang="ts">
/**
 * 主题标签（DESIGN §3.10）：与 `SourceTag` / `ProjectTag` 同几何——20 高 / 11px / 左右 6、圆圈点 6。
 *
 * 三色**只来自 props**（任务行 `theme` 快照或 meta.themes），组件内不解析 hex、不派生第二套色值；
 * 缺色回落 slate 令牌，缺名称渲染「—」（页面按需不渲染，绝不产生空标签）。
 * 停用后缀（已停用）用文字表达，不靠 opacity 降低对比度。
 */
import { computed } from 'vue'

const props = withDefaults(
  defineProps<{
    name?: string | null
    color?: string | null
    softColor?: string | null
    borderColor?: string | null
    enabled?: boolean
  }>(),
  // Boolean 类型 prop 缺省会被 Vue 转成 false；主题默认是启用，只有显式 false 才加停用后缀
  { enabled: true },
)

const hasName = computed(() => Boolean(props.name?.trim()))
const label = computed(() => {
  const name = props.name?.trim() ?? ''
  if (!name) return '—'
  return props.enabled === false ? `${name}（已停用）` : name
})

/** 未知/未挂主题只用文字兜底，统一 slate，避免与主题色混淆 */
const style = computed(() => ({
  color: hasName.value ? props.color || 'var(--src-slate-text)' : 'var(--c-ink-400)',
  backgroundColor: hasName.value ? props.softColor || 'var(--src-slate-soft)' : 'transparent',
  borderColor: hasName.value ? props.borderColor || 'var(--src-slate-border)' : 'var(--c-line)',
}))

const dotStyle = computed(() =>
  hasName.value ? { backgroundColor: props.color || 'var(--src-slate-text)' } : undefined,
)
</script>

<template>
  <span
    class="theme-tag"
    :class="{ 'theme-tag--empty': !hasName, 'theme-tag--disabled': hasName && enabled === false }"
    :style="style"
    :title="hasName ? label : undefined"
  >
    <span v-if="hasName" class="theme-tag__dot" :style="dotStyle" aria-hidden="true" />
    <span class="theme-tag__text">{{ label }}</span>
  </span>
</template>

<style scoped>
.theme-tag {
  display: inline-flex;
  align-items: center;
  gap: 4px;
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
.theme-tag__dot {
  flex: none;
  width: 6px;
  height: 6px;
  border-radius: 50%;
}
.theme-tag__text {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.theme-tag--empty {
  border-style: dashed;
  background: transparent;
}
.theme-tag--disabled .theme-tag__text {
  font-weight: 400;
}
</style>
