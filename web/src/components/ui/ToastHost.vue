<script setup lang="ts">
/** 全局 Toast 容器（DESIGN §5.12）：右上角，最多 2 条，3s 自动消失，左 3px 语义条。 */
import { useUiStore } from '@/stores/ui'

const ui = useUiStore()

const TONE_TEXT: Record<string, string> = {
  success: '成功',
  danger: '错误',
  warning: '提醒',
  info: '提示',
}
</script>

<template>
  <div class="toast-host" aria-live="polite" aria-atomic="false">
    <TransitionGroup name="toast">
      <div
        v-for="item in ui.toasts"
        :key="item.id"
        class="toast"
        :class="`toast--${item.tone}`"
        :role="item.tone === 'danger' ? 'alert' : 'status'"
      >
        <span class="toast__bar" aria-hidden="true" />
        <span class="visually-hidden">{{ TONE_TEXT[item.tone] }}：</span>
        <p class="toast__message">{{ item.message }}</p>
        <button type="button" class="toast__close" aria-label="关闭提示" @click="ui.dismissToast(item.id)">
          <svg width="12" height="12" viewBox="0 0 12 12" aria-hidden="true">
            <path d="M3 3l6 6M9 3l-6 6" fill="none" stroke="currentColor" stroke-width="1.4" stroke-linecap="round" />
          </svg>
        </button>
      </div>
    </TransitionGroup>
  </div>
</template>

<style scoped>
.toast-host {
  position: fixed;
  top: calc(var(--topbar-h) + var(--sp-3));
  right: var(--sp-6);
  z-index: var(--z-toast);
  display: flex;
  flex-direction: column;
  gap: var(--sp-2);
  pointer-events: none;
}
.toast {
  position: relative;
  display: flex;
  align-items: flex-start;
  gap: var(--sp-2);
  width: 360px;
  max-width: calc(100vw - var(--sp-6) * 2);
  padding: var(--sp-3) var(--sp-3) var(--sp-3) var(--sp-4);
  background: var(--c-surface);
  border: 1px solid var(--c-line);
  border-radius: var(--r-lg);
  box-shadow: var(--sh-2);
  pointer-events: auto;
  overflow: hidden;
}
.toast__bar {
  position: absolute;
  left: 0;
  top: 0;
  bottom: 0;
  width: 3px;
  background: var(--c-ink-400);
}
.toast--success .toast__bar {
  background: var(--c-success);
}
.toast--danger .toast__bar {
  background: var(--c-danger);
}
.toast--warning .toast__bar {
  background: var(--c-warn);
}
.toast--info .toast__bar {
  background: var(--c-info);
}
.toast__message {
  flex: 1;
  margin: 0;
  font-size: var(--fs-body-sm);
  line-height: var(--lh-body-sm);
  color: var(--c-ink-900);
  word-break: break-word;
}
.toast__close {
  flex: none;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 20px;
  height: 20px;
  border: 0;
  border-radius: var(--r-sm);
  background: transparent;
  color: var(--c-ink-400);
  cursor: pointer;
}
.toast__close:hover {
  background: var(--c-line-soft);
  color: var(--c-ink-700);
}
.toast__close:focus-visible {
  outline: none;
  box-shadow: var(--focus-ring);
}

.toast-enter-active,
.toast-leave-active {
  transition:
    opacity var(--t-toggle) var(--ease),
    transform var(--t-toggle) var(--ease);
}
.toast-enter-from,
.toast-leave-to {
  opacity: 0;
  transform: translateY(-6px);
}
@media (prefers-reduced-motion: reduce) {
  .toast-enter-active,
  .toast-leave-active {
    transition: none;
  }
}
</style>
