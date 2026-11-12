<script setup lang="ts">
/**
 * 甘特条（DESIGN §3.1 / §4.1）：条上不写字，信息全部进 tooltip。
 * 逾期红描边、按创建日推算的虚线左帽、窗口右界外的红色三角夹标都只在这个文件出现一次；
 * 是否逾期、逾期几天、是否风险一律读接口字段，这里没有任何日期比较（AC-7）。
 */
import { computed } from 'vue'
import type { GanttTask } from '@/types/api'
import { shortDate } from '@/composables/useGanttWindow'

const props = defineProps<{
  task: GanttTask
  x: number
  width: number
  pinnedStart: boolean
  pinnedEnd: boolean
}>()

defineEmits<{ (event: 'open', id: number): void }>()

const range = computed(() => `${shortDate(props.task.startDate)} → ${shortDate(props.task.dueDate)}`)
const showOverdue = computed(() => props.task.isOverdue && props.width >= 64)
const overdueText = computed(() => `逾期 ${props.task.overdueDays} 天`)
const overdueLabel = computed(() =>
  props.pinnedStart && props.width >= 150 ? `${overdueText.value}（超出窗口）` : overdueText.value,
)

const aria = computed(() => {
  const parts = [
    props.task.title,
    `负责人 ${props.task.owner?.name ?? '未指派'}`,
    `来源 ${props.task.sourceLabel || '无来源'}`,
    `起止 ${range.value}`,
    props.task.statusLabel,
  ]
  if (props.task.isOverdue) {
    parts.push(`${overdueText.value}${props.pinnedStart ? '（超出窗口）' : ''}`)
  }
  if (props.task.estimatedStart) parts.push('起点按创建日推算')
  if (props.pinnedEnd) parts.push('截止在窗口右侧之外')
  return parts.join('，')
})
</script>

<template>
  <button
    class="bar"
    :class="[`bar--${task.status}`, { 'bar--overdue': task.isOverdue, 'bar--estimated': task.estimatedStart }]"
    :style="{ left: `${x}px`, width: `${width}px` }"
    type="button"
    :aria-label="aria"
    @click="$emit('open', task.id)"
  >
    <span class="bar__cap" :class="{ 'bar__cap--estimated': task.estimatedStart }" aria-hidden="true" />
    <span v-if="task.estimatedStart" class="bar__approx" aria-hidden="true">≈</span>
    <span
      v-if="showOverdue"
      class="bar__overdue"
      :title="`${overdueText}${task.pinnedStart ? '（超出窗口）' : ''}`"
    >{{ overdueLabel }}</span>
    <span
      v-if="pinnedEnd"
      class="bar__clamp"
      :title="`风险 · 截止 ${shortDate(task.dueDate)}（窗口外）`"
      aria-hidden="true"
    />
    <span class="tip" role="tooltip">
      <span class="tip__title">{{ task.title }}</span>
      <span class="tip__row">{{ task.owner?.name ?? '未指派' }}</span>
      <span class="tip__row">{{ task.sourceLabel || '无来源' }}</span>
      <span class="tip__row">{{ range }}<template v-if="task.estimatedStart">（按创建日推算）</template></span>
      <span class="tip__row">
        {{ task.statusLabel }}<template v-if="task.isOverdue"> · {{ overdueText }}{{ pinnedStart ? '（超出窗口）' : '' }}</template>
        <template v-else-if="task.isAtRisk"> · 风险</template>
      </span>
    </span>
  </button>
</template>

<style scoped>
.bar {
  position: absolute;
  top: 50%;
  transform: translateY(-50%);
  height: 18px;
  padding: 0;
  border: 1px solid var(--c-line);
  border-radius: 3px;
  background: var(--c-surface-hover);
  cursor: pointer;
  text-align: left;
  transition: box-shadow var(--t-hover) var(--ease);
  --bar-accent: var(--c-ink-400);
}
.bar--IN_PROGRESS {
  background: var(--c-info-soft);
  border-color: var(--c-info-border);
  --bar-accent: var(--c-info);
}
.bar--PENDING_ACCEPT {
  background: var(--c-warn-soft);
  border-color: var(--c-warn-border);
  --bar-accent: var(--c-warn);
}
.bar--DONE {
  background: var(--c-success-soft);
  border-color: var(--c-success-border);
  --bar-accent: var(--c-success);
}
.bar--CANCELLED {
  background: transparent;
  border: 1px dashed var(--c-line);
  --bar-accent: var(--c-ink-400);
}
.bar--CANCELLED::after {
  content: '';
  position: absolute;
  left: 4px;
  right: 4px;
  top: 50%;
  border-top: 2px dashed var(--c-line);
}
.bar--overdue {
  border: 1.5px solid var(--c-danger);
  --bar-accent: var(--c-danger);
}
.bar:hover {
  box-shadow: 0 0 0 2px rgba(27, 77, 143, 0.12);
}
.bar:focus-visible {
  outline: none;
  box-shadow: var(--focus-ring);
}
.bar__cap {
  position: absolute;
  left: 0;
  top: 0;
  bottom: 0;
  width: 3px;
  border-radius: 2px 0 0 2px;
  background: var(--bar-accent);
}
.bar__cap--estimated {
  background: repeating-linear-gradient(180deg, var(--bar-accent) 0 2px, transparent 2px 4px);
}
.bar__approx {
  position: absolute;
  left: 6px;
  top: 50%;
  transform: translateY(-50%);
  font-size: 10px;
  line-height: 1;
  color: var(--c-ink-500);
}
.bar__overdue {
  position: absolute;
  right: 4px;
  top: 50%;
  transform: translateY(-50%);
  height: 14px;
  padding: 0 4px;
  border-radius: 2px;
  background: var(--c-danger);
  color: #fff;
  font-size: 10px;
  line-height: 14px;
  white-space: nowrap;
}
.bar__clamp {
  position: absolute;
  right: -5px;
  top: -4px;
  width: 0;
  height: 0;
  border-top: 8px solid var(--c-danger);
  border-left: 8px solid transparent;
}
.tip {
  position: absolute;
  top: calc(100% + 8px);
  left: 0;
  z-index: var(--z-dropdown);
  display: flex;
  flex-direction: column;
  gap: 2px;
  width: max-content;
  max-width: 280px;
  padding: var(--sp-2);
  border-radius: var(--r-sm);
  background: var(--c-ink-900);
  color: #fff;
  font-size: var(--fs-caption);
  line-height: 18px;
  box-shadow: var(--sh-3);
  opacity: 0;
  visibility: hidden;
  transition: opacity var(--t-hover) var(--ease);
  pointer-events: none;
}
.tip__title {
  font-weight: 600;
}
.tip__row {
  white-space: normal;
}
.bar:hover .tip,
.bar:focus-visible .tip {
  opacity: 1;
  visibility: visible;
}
</style>
