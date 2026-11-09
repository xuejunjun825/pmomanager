<script setup lang="ts">
/**
 * 逾期 Pill（DESIGN §5.5）：红色只出现在这里和破坏性按钮上。
 * 天数一律服务端算好（is_overdue / overdue_days），前端不做日期推算。
 */
import { computed } from 'vue'

const props = defineProps<{
  /** 逾期天数；<=0 或 null 时不渲染（调用方无需再判断） */
  days?: number | null
  /** 即将到期用中性提示，不用红色 */
  variant?: 'overdue' | 'due-soon'
}>()

const visible = computed(() => props.variant === 'due-soon' || (props.days ?? 0) > 0)
const text = computed(() => {
  if (props.variant === 'due-soon') return '即将到期'
  return `逾期 ${props.days} 天`
})
</script>

<template>
  <span v-if="visible" class="pill" :class="`pill--${variant ?? 'overdue'}`" :title="text">
    <svg width="12" height="12" viewBox="0 0 16 16" aria-hidden="true">
      <circle cx="8" cy="8" r="5.5" fill="none" stroke="currentColor" stroke-width="1.5" />
      <path d="M8 5.2v3.2l2.1 1.2" fill="none" stroke="currentColor" stroke-width="1.5" stroke-linecap="round" />
    </svg>
    <span>{{ text }}</span>
  </span>
</template>

<style scoped>
.pill {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  height: 22px;
  padding: 0 var(--sp-2);
  border-radius: var(--r-pill);
  font-size: var(--fs-caption);
  line-height: var(--lh-caption);
  font-weight: 600;
  white-space: nowrap;
  cursor: default;
  font-variant-numeric: tabular-nums;
}
.pill--overdue {
  background: var(--c-danger-soft);
  border: 1px solid var(--c-danger-border);
  color: var(--c-danger-text);
}
.pill--due-soon {
  background: var(--c-warn-soft);
  border: 1px solid var(--c-warn-border);
  color: var(--c-warn);
}
</style>
