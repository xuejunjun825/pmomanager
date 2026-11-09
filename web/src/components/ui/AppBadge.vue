<script setup lang="ts">
/** 通用徽标（DESIGN §5.4）：所有语义色都从令牌来，组件不写死色值。 */
import type { BadgeTone } from '@/types/enums'

withDefaults(
  defineProps<{
    tone?: BadgeTone
    /** 前置圆点：比纯色块更容易在灰度打印时区分 */
    dot?: boolean
    size?: 'sm' | 'md'
  }>(),
  { tone: 'neutral', dot: false, size: 'md' },
)
</script>

<template>
  <span class="badge" :class="[`badge--${tone}`, `badge--${size}`]">
    <span v-if="dot" class="badge__dot" aria-hidden="true" />
    <slot />
  </span>
</template>

<style scoped>
.badge {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  height: 22px;
  padding: 0 var(--sp-2);
  border: 1px solid transparent;
  border-radius: var(--r-sm);
  font-size: var(--fs-caption);
  line-height: var(--lh-caption);
  font-weight: 500;
  white-space: nowrap;
}
.badge--sm {
  height: 20px;
  padding: 0 6px;
  font-size: var(--fs-micro);
  line-height: var(--lh-micro);
}
.badge__dot {
  width: 6px;
  height: 6px;
  border-radius: 50%;
  background: currentColor;
  flex: none;
}

.badge--neutral {
  background: var(--c-line-soft);
  border-color: var(--c-line);
  color: var(--c-ink-600);
}
.badge--primary {
  background: var(--c-primary-soft);
  border-color: var(--c-primary-border);
  color: var(--c-primary);
}
.badge--success {
  background: var(--c-success-soft);
  border-color: var(--c-success-border);
  color: var(--c-success);
}
.badge--warning {
  background: var(--c-warn-soft);
  border-color: var(--c-warn-border);
  color: var(--c-warn);
}
.badge--danger {
  background: var(--c-danger-soft);
  border-color: var(--c-danger-border);
  color: var(--c-danger-text);
}
.badge--info {
  background: var(--c-info-soft);
  border-color: var(--c-info-border);
  color: var(--c-info);
}
.badge--purple {
  background: var(--c-purple-soft);
  border-color: #d9d6fe;
  color: var(--c-purple);
}
</style>
