<script setup lang="ts">
/**
 * 甘特摘要条（DESIGN §3.1 / AC-11）：五个数字点击即筛。
 * 逾期 / 风险是全局口径（与看板同值），其余三个是本窗口口径——两者混用时数字会被
 * 误读成「窗口里只有 3 条逾期」，所以提示文字常驻在数字旁边。
 */
import { computed } from 'vue'
import type { GanttQuickFilter, GanttSummary } from '@/types/api'

const props = defineProps<{
  summary: GanttSummary
  active: GanttQuickFilter
  onlyRisk: boolean
  showDone: boolean
}>()

defineEmits<{
  (event: 'pick', filter: GanttQuickFilter): void
  (event: 'update:onlyRisk', value: boolean): void
}>()

type ChipKey = Exclude<GanttQuickFilter, ''>

const chips = computed<Array<{ key: ChipKey; label: string; value: number; danger: boolean }>>(() => {
  const list: Array<{ key: ChipKey; label: string; value: number; danger: boolean }> = [
    { key: 'overdue', label: '逾期', value: props.summary.overdue, danger: true },
    { key: 'risk', label: '风险', value: props.summary.risk, danger: true },
    { key: 'inProgress', label: '进行中', value: props.summary.inProgress, danger: false },
    { key: 'pendingAccept', label: '待验收', value: props.summary.pendingAccept, danger: false },
  ]
  if (props.showDone) list.push({ key: 'done', label: '已完成', value: props.summary.done, danger: false })
  return list
})
</script>

<template>
  <section class="summary" aria-label="甘特摘要">
    <div class="summary__chips">
      <button
        v-for="chip in chips"
        :key="chip.key"
        class="chip"
        :class="{ 'chip--active': active === chip.key, 'chip--danger': chip.danger }"
        type="button"
        :aria-pressed="active === chip.key"
        @click="$emit('pick', chip.key)"
      >
        <span class="chip__label">{{ chip.label }}</span>
        <span class="chip__num">{{ chip.value }}</span>
      </button>
    </div>
    <button
      class="switch"
      :class="{ 'switch--on': onlyRisk }"
      type="button"
      :aria-pressed="onlyRisk"
      @click="$emit('update:onlyRisk', !onlyRisk)"
    >
      <span class="switch__track" aria-hidden="true"><span class="switch__knob" /></span>
      仅看逾期与风险
    </button>
    <p class="summary__hint">逾期 / 风险为全部任务（与看板同值）；进行中 / 待验收 / 已完成为本窗口</p>
  </section>
</template>

<style scoped>
.summary {
  display: flex;
  align-items: center;
  gap: var(--sp-3);
  flex-wrap: wrap;
  padding: var(--sp-3);
  border: 1px solid var(--c-line);
  border-radius: var(--r-md);
  background: var(--c-surface);
}
.summary__chips {
  display: flex;
  align-items: center;
  gap: var(--sp-2);
  flex-wrap: wrap;
}
.chip {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  height: 32px;
  padding: 0 var(--sp-3);
  border: 1px solid var(--c-line);
  border-radius: var(--r-md);
  background: var(--c-surface);
  color: var(--c-ink-600);
  font-size: var(--fs-caption);
  cursor: pointer;
  transition: border-color var(--t-hover) var(--ease), background var(--t-hover) var(--ease);
}
.chip:hover {
  border-color: var(--c-primary-border);
  color: var(--c-ink-900);
}
.chip:focus-visible {
  outline: none;
  box-shadow: var(--focus-ring);
}
.chip--active {
  border-color: var(--c-primary);
  background: var(--c-primary-soft);
  color: var(--c-primary);
}
.chip__num {
  font-weight: 600;
  font-variant-numeric: tabular-nums;
  color: var(--c-ink-900);
}
.chip--danger .chip__num {
  color: var(--c-danger-text);
}
.chip--active .chip__num {
  color: inherit;
}
.switch {
  display: inline-flex;
  align-items: center;
  gap: var(--sp-2);
  height: 32px;
  padding: 0 var(--sp-3);
  border: 1px solid var(--c-line);
  border-radius: var(--r-md);
  background: var(--c-surface);
  color: var(--c-ink-600);
  font-size: var(--fs-caption);
  cursor: pointer;
}
.switch:hover {
  border-color: var(--c-primary-border);
}
.switch:focus-visible {
  outline: none;
  box-shadow: var(--focus-ring);
}
.switch__track {
  position: relative;
  width: 26px;
  height: 14px;
  border-radius: 7px;
  background: var(--c-line);
  transition: background var(--t-toggle) var(--ease);
}
.switch__knob {
  position: absolute;
  top: 2px;
  left: 2px;
  width: 10px;
  height: 10px;
  border-radius: 50%;
  background: #fff;
  transition: transform var(--t-toggle) var(--ease);
}
.switch--on {
  border-color: var(--c-danger-border);
  color: var(--c-danger-text);
}
.switch--on .switch__track {
  background: var(--c-danger);
}
.switch--on .switch__knob {
  transform: translateX(12px);
}
.summary__hint {
  margin: 0;
  font-size: var(--fs-micro);
  line-height: 16px;
  color: var(--c-ink-500);
}
</style>
