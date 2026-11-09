<script setup lang="ts">
/**
 * 任务状态徽标（DESIGN §5.3）：颜色 + 文字 + 图标三重编码。
 * 色弱或灰度打印时仍能区分，所以不是「只靠颜色」。
 */
import { computed } from 'vue'
import { TASK_STATUS_LABELS, type BadgeTone, type TaskStatus } from '@/types/enums'

const props = defineProps<{ status: TaskStatus; size?: 'sm' | 'md' }>()

const TONES: Record<TaskStatus, BadgeTone> = {
  PENDING: 'neutral',
  IN_PROGRESS: 'info',
  PENDING_ACCEPT: 'warning',
  DONE: 'success',
  CANCELLED: 'neutral',
}

const PATHS: Record<TaskStatus, string> = {
  PENDING: 'M8 2.5a5.5 5.5 0 1 0 0 11 5.5 5.5 0 0 0 0-11Z',
  IN_PROGRESS: 'M8 2.5a5.5 5.5 0 0 1 0 11V2.5Z',
  PENDING_ACCEPT: 'M8 2.5a5.5 5.5 0 1 0 0 11 5.5 5.5 0 0 0 0-11ZM8 4.8v3.5l2.2 1.3',
  DONE: 'M3.6 8.4l2.9 2.9 5.9-6.3',
  CANCELLED: 'M4.6 8h6.8',
}

const label = computed(() => TASK_STATUS_LABELS[props.status])
const tone = computed(() => TONES[props.status])
const path = computed(() => PATHS[props.status])
</script>

<template>
  <span
    class="status"
    :class="[`status--${tone}`, { 'status--cancelled': status === 'CANCELLED', 'status--sm': size === 'sm' }]"
    :title="label"
  >
    <svg class="status__icon" width="12" height="12" viewBox="0 0 16 16" aria-hidden="true">
      <path :d="path" fill="none" stroke="currentColor" stroke-width="1.5" stroke-linecap="round" stroke-linejoin="round" />
    </svg>
    <span>{{ label }}</span>
  </span>
</template>

<style scoped>
.status {
  display: inline-flex;
  align-items: center;
  gap: 5px;
  height: 22px;
  padding: 0 var(--sp-2);
  border: 1px solid transparent;
  border-radius: var(--r-sm);
  font-size: var(--fs-caption);
  line-height: var(--lh-caption);
  font-weight: 500;
  white-space: nowrap;
}
.status--sm {
  height: 20px;
  padding: 0 6px;
  font-size: var(--fs-micro);
}
.status__icon {
  flex: none;
}
.status--neutral {
  background: var(--c-line-soft);
  border-color: var(--c-line);
  color: var(--c-ink-600);
}
.status--info {
  background: var(--c-info-soft);
  border-color: var(--c-info-border);
  color: var(--c-info);
}
.status--warning {
  background: var(--c-warn-soft);
  border-color: var(--c-warn-border);
  color: var(--c-warn);
}
.status--success {
  background: var(--c-success-soft);
  border-color: var(--c-success-border);
  color: var(--c-success);
}
.status--cancelled {
  background: transparent;
  border-color: var(--c-line);
  border-style: dashed;
  color: var(--c-ink-500);
}
</style>
