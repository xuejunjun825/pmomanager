<script setup lang="ts">
/**
 * 矩阵单元格（DESIGN §4.8 / §5）：88×48，数字 16/600 等宽。
 * 0 一律不可点（灰色，鼠标不给手型）；>0 且给了 to 才是按钮，跳任务池后数字必须与格内一致。
 * 合计行等无法用一个筛选表达的数字直接不传 to，渲染成静态数字。
 */
import { computed } from 'vue'
import { RouterLink, type RouteLocationRaw } from 'vue-router'
import { formatNumber } from '@/utils/format'

const props = withDefaults(
  defineProps<{
    value: number
    to?: RouteLocationRaw | null
    /** 逾期列：唯一允许红字的数字 */
    danger?: boolean
    /** 无障碍名称，如「张三 的 进行中」 */
    label?: string
  }>(),
  { to: null, danger: false, label: '' },
)

const display = computed(() => formatNumber(props.value))
const clickable = computed(() => Boolean(props.to) && props.value > 0)
const hint = computed(() => (props.label ? `查看${props.label}的任务（${display.value} 条）` : undefined))
</script>

<template>
  <RouterLink v-if="clickable" :to="to!" class="mc mc--link" :class="{ 'mc--danger': danger }" :title="hint">
    {{ display }}
  </RouterLink>
  <span
    v-else
    class="mc"
    :class="{ 'mc--zero': value === 0, 'mc--danger': danger && value > 0 }"
  >
    {{ display }}
  </span>
</template>

<style scoped>
.mc {
  display: inline-flex;
  align-items: center;
  justify-content: flex-end;
  min-width: 100%;
  height: 100%;
  padding: 0 var(--sp-3);
  font-size: var(--fs-h3);
  font-weight: 600;
  font-variant-numeric: tabular-nums;
  color: var(--c-ink-900);
}
.mc--zero {
  color: var(--c-ink-400);
  cursor: default;
}
.mc--danger {
  color: var(--c-danger-text);
}
.mc--link {
  color: inherit;
  text-decoration: none;
  border-radius: var(--r-sm);
  transition:
    background-color var(--t-hover) var(--ease),
    color var(--t-hover) var(--ease);
}
.mc--link:hover {
  color: var(--c-primary);
  background: var(--c-primary-soft);
  text-decoration: underline;
}
.mc--link:focus-visible {
  outline: 2px solid var(--c-primary);
  outline-offset: 2px;
}
.mc--link.mc--danger:hover {
  color: var(--c-danger-text);
  background: var(--c-danger-soft);
}
</style>
