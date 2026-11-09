<script setup lang="ts">
/**
 * 按钮（DESIGN §5.1）：32 高 / 紧凑 28 / 大 40；loading 时保留宽度不跳动。
 * icon-only 必须给 aria-label（视觉上无文字，读屏只剩图形）。
 */
import { computed } from 'vue'

type Variant = 'primary' | 'secondary' | 'ghost' | 'danger-outline'
type ButtonSize = 'compact' | 'default' | 'large'

const props = withDefaults(
  defineProps<{
    variant?: Variant
    size?: ButtonSize
    type?: 'button' | 'submit' | 'reset'
    disabled?: boolean
    loading?: boolean
    /** 提交中的替换文案（如「登录中…」）：给了就保留可见文字 + 行内 spinner */
    loadingText?: string
    block?: boolean
    iconOnly?: boolean
    ariaLabel?: string
  }>(),
  { variant: 'secondary', size: 'default', type: 'button', disabled: false, loading: false, block: false, iconOnly: false },
)

if (import.meta.env.DEV && props.iconOnly && !props.ariaLabel) {
  console.warn('[AppButton] icon-only 按钮必须提供 ariaLabel')
}

const classes = computed(() => [
  'btn',
  `btn--${props.variant}`,
  `btn--${props.size}`,
  { 'btn--block': props.block, 'btn--icon': props.iconOnly, 'is-loading': props.loading },
])

const isInert = computed(() => props.disabled || props.loading)
</script>

<template>
  <button
    :type="type"
    :class="classes"
    :disabled="isInert"
    :aria-label="ariaLabel"
    :aria-busy="loading || undefined"
    :aria-disabled="isInert || undefined"
  >
    <span v-if="loading && loadingText" class="btn__spinner btn__spinner--inline" aria-hidden="true" />
    <span class="btn__label" :class="{ 'is-invisible': loading && !loadingText }">
      <slot>{{ loading && loadingText ? loadingText : '' }}</slot>
    </span>
    <span v-if="loading && !loadingText" class="btn__spinner" aria-hidden="true" />
  </button>
</template>

<style scoped>
.btn {
  position: relative;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  gap: var(--sp-2);
  height: 32px;
  padding: 0 var(--sp-3);
  border: 1px solid transparent;
  border-radius: var(--r-md);
  font-family: var(--font-sans);
  font-size: var(--fs-body-sm);
  font-weight: 500;
  line-height: 1;
  white-space: nowrap;
  cursor: pointer;
  transition:
    background-color var(--t-hover) var(--ease),
    border-color var(--t-hover) var(--ease),
    color var(--t-hover) var(--ease),
    box-shadow var(--t-hover) var(--ease);
  -webkit-tap-highlight-color: transparent;
}
.btn--compact {
  height: 28px;
  padding: 0 var(--sp-2);
  font-size: var(--fs-caption);
}
.btn--large {
  height: 40px;
  padding: 0 var(--sp-4);
  font-size: var(--fs-body);
}
.btn--block {
  width: 100%;
}
.btn--icon {
  width: 32px;
  padding: 0;
}
.btn--icon.btn--compact {
  width: 28px;
}
.btn--icon.btn--large {
  width: 40px;
}

.btn--primary {
  background: var(--c-primary);
  color: #fff;
}
.btn--primary:hover:not(:disabled) {
  background: var(--c-primary-hover);
}
.btn--primary:active:not(:disabled) {
  background: var(--c-primary-active);
}

.btn--secondary {
  background: var(--c-surface);
  border-color: var(--c-line);
  color: var(--c-ink-700);
}
.btn--secondary:hover:not(:disabled) {
  background: var(--c-surface-hover);
  border-color: var(--c-ink-400);
}

.btn--ghost {
  background: transparent;
  color: var(--c-ink-600);
}
.btn--ghost:hover:not(:disabled) {
  background: var(--c-line-soft);
  color: var(--c-ink-900);
}

.btn--danger-outline {
  background: var(--c-surface);
  border-color: var(--c-danger-border);
  color: var(--c-danger-text);
}
.btn--danger-outline:hover:not(:disabled) {
  background: var(--c-danger-soft);
}

.btn:active:not(:disabled) {
  transform: translateY(0.5px);
}
.btn:focus-visible {
  outline: none;
  box-shadow: var(--focus-ring);
}
.btn:disabled:not(.is-loading) {
  cursor: not-allowed;
  opacity: 0.5;
}
.btn.is-loading {
  cursor: progress;
  opacity: 0.8;
}

.btn__label {
  display: inline-flex;
  align-items: center;
  gap: var(--sp-2);
}
.btn__label.is-invisible {
  visibility: hidden;
}
.btn__spinner {
  position: absolute;
  top: 50%;
  left: 50%;
  width: 14px;
  height: 14px;
  margin: -7px 0 0 -7px;
  border: 1.5px solid currentColor;
  border-top-color: transparent;
  border-radius: 50%;
  animation: btn-spin 640ms linear infinite;
}
.btn__spinner--inline {
  position: static;
  margin: 0;
}
@keyframes btn-spin {
  to {
    transform: rotate(360deg);
  }
}
@media (prefers-reduced-motion: reduce) {
  .btn__spinner {
    animation-duration: 1600ms;
  }
}
</style>
