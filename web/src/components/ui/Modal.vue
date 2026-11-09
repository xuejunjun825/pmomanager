<script setup lang="ts">
/**
 * 对话框（DESIGN §5.15）：用于确认与强制改密。
 * closable=false 时（首次登录改密）没有 ×、Esc 无效、遮罩不可关。
 */
import { nextTick, onBeforeUnmount, ref, watch } from 'vue'

const props = withDefaults(
  defineProps<{
    open: boolean
    title?: string
    description?: string
    size?: 'sm' | 'md' | 'lg'
    closable?: boolean
    closeOnOverlay?: boolean
  }>(),
  { title: '', description: '', size: 'md', closable: true, closeOnOverlay: true },
)

const emit = defineEmits<{ close: [] }>()

const panelRef = ref<HTMLElement | null>(null)
let restoreOverflow = ''
let listening = false

function requestClose(): void {
  if (!props.closable) return
  emit('close')
}

function onKeydown(event: KeyboardEvent): void {
  if (event.key === 'Escape') requestClose()
}

function lock(): void {
  restoreOverflow = document.body.style.overflow
  document.body.style.overflow = 'hidden'
  if (!listening) {
    document.addEventListener('keydown', onKeydown)
    listening = true
  }
}

function unlock(): void {
  document.body.style.overflow = restoreOverflow
  if (listening) {
    document.removeEventListener('keydown', onKeydown)
    listening = false
  }
}

watch(
  () => props.open,
  async (open) => {
    if (open) {
      lock()
      await nextTick()
      panelRef.value?.focus()
    } else {
      unlock()
    }
  },
  { immediate: true },
)

onBeforeUnmount(unlock)
</script>

<template>
  <Teleport to="body">
    <Transition name="modal">
      <div v-if="open" class="modal">
        <div
          class="modal__overlay"
          @click="closeOnOverlay && closable && emit('close')"
        />
        <section
          ref="panelRef"
          class="modal__panel"
          :class="`modal__panel--${size}`"
          role="dialog"
          aria-modal="true"
          :aria-label="title || '对话框'"
          tabindex="-1"
        >
          <header class="modal__head">
            <div>
              <h2 class="modal__title">{{ title }}</h2>
              <p v-if="description" class="modal__desc">{{ description }}</p>
            </div>
            <button v-if="closable" type="button" class="modal__close" aria-label="关闭" @click="emit('close')">
              <svg width="16" height="16" viewBox="0 0 16 16" aria-hidden="true">
                <path d="M4 4l8 8M12 4l-8 8" fill="none" stroke="currentColor" stroke-width="1.5" stroke-linecap="round" />
              </svg>
            </button>
          </header>
          <div class="modal__body">
            <slot />
          </div>
          <footer v-if="$slots.footer" class="modal__foot">
            <slot name="footer" />
          </footer>
        </section>
      </div>
    </Transition>
  </Teleport>
</template>

<style scoped>
.modal {
  position: fixed;
  inset: 0;
  z-index: var(--z-overlay);
  display: flex;
  align-items: center;
  justify-content: center;
  padding: var(--sp-4);
}
.modal__overlay {
  position: absolute;
  inset: 0;
  background: rgba(15, 23, 42, 0.38);
}
.modal__panel {
  position: relative;
  width: 100%;
  max-height: 86vh;
  overflow-y: auto;
  background: var(--c-surface);
  border-radius: var(--r-xl);
  box-shadow: var(--sh-3);
  outline: none;
}
.modal__panel--sm {
  max-width: 400px;
}
.modal__panel--md {
  max-width: 480px;
}
.modal__panel--lg {
  max-width: 640px;
}
.modal__head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: var(--sp-3);
  padding: var(--sp-5) var(--sp-5) 0;
}
.modal__title {
  margin: 0;
  font-size: var(--fs-h3);
  line-height: var(--lh-h3);
  font-weight: 600;
  color: var(--c-ink-900);
}
.modal__desc {
  margin: 4px 0 0;
  font-size: var(--fs-body-sm);
  line-height: var(--lh-body-sm);
  color: var(--c-ink-500);
}
.modal__close {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 28px;
  height: 28px;
  flex: none;
  border: 0;
  border-radius: var(--r-md);
  background: transparent;
  color: var(--c-ink-500);
  cursor: pointer;
}
.modal__close:hover {
  background: var(--c-line-soft);
  color: var(--c-ink-900);
}
.modal__close:focus-visible {
  outline: none;
  box-shadow: var(--focus-ring);
}
.modal__body {
  padding: var(--sp-4) var(--sp-5);
}
.modal__foot {
  display: flex;
  align-items: center;
  justify-content: flex-end;
  gap: var(--sp-2);
  padding: var(--sp-3) var(--sp-5) var(--sp-5);
}

.modal-enter-active,
.modal-leave-active {
  transition: opacity var(--t-toggle) var(--ease);
}
.modal-enter-active .modal__panel,
.modal-leave-active .modal__panel {
  transition: transform var(--t-toggle) var(--ease);
}
.modal-enter-from,
.modal-leave-to {
  opacity: 0;
}
.modal-enter-from .modal__panel,
.modal-leave-to .modal__panel {
  transform: translateY(8px) scale(0.99);
}
@media (prefers-reduced-motion: reduce) {
  .modal-enter-active,
  .modal-leave-active,
  .modal-enter-active .modal__panel,
  .modal-leave-active .modal__panel {
    transition: none;
  }
}
</style>
