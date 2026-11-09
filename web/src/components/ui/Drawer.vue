<script setup lang="ts">
/**
 * 右侧抽屉（DESIGN §5.14）：480 宽，表单主容器。
 * 关闭方式：Esc / 遮罩 / 头部 ×；打开时锁滚动并把焦点移入面板。
 */
import { nextTick, onBeforeUnmount, ref, watch } from 'vue'

const props = withDefaults(
  defineProps<{
    open: boolean
    title?: string
    description?: string
    width?: number
    closeOnOverlay?: boolean
  }>(),
  { title: '', description: '', width: 480, closeOnOverlay: true },
)

const emit = defineEmits<{ close: [] }>()

const panelRef = ref<HTMLElement | null>(null)
let restoreOverflow = ''
let listening = false

function onKeydown(event: KeyboardEvent): void {
  if (event.key === 'Escape') {
    event.stopPropagation()
    emit('close')
  }
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
    <Transition name="drawer">
      <div v-if="open" class="drawer">
        <div class="drawer__overlay" @click="closeOnOverlay && emit('close')" />
        <section
          ref="panelRef"
          class="drawer__panel"
          role="dialog"
          aria-modal="true"
          :aria-label="title || '抽屉'"
          tabindex="-1"
          :style="{ width: `${width}px` }"
        >
          <header class="drawer__head">
            <div class="drawer__titles">
              <h2 class="drawer__title">{{ title }}</h2>
              <p v-if="description" class="drawer__desc">{{ description }}</p>
            </div>
            <button type="button" class="drawer__close" aria-label="关闭" @click="emit('close')">
              <svg width="16" height="16" viewBox="0 0 16 16" aria-hidden="true">
                <path d="M4 4l8 8M12 4l-8 8" fill="none" stroke="currentColor" stroke-width="1.5" stroke-linecap="round" />
              </svg>
            </button>
          </header>
          <div class="drawer__body">
            <slot />
          </div>
          <footer v-if="$slots.footer" class="drawer__foot">
            <slot name="footer" />
          </footer>
        </section>
      </div>
    </Transition>
  </Teleport>
</template>

<style scoped>
.drawer {
  position: fixed;
  inset: 0;
  z-index: var(--z-overlay);
}
.drawer__overlay {
  position: absolute;
  inset: 0;
  background: rgba(15, 23, 42, 0.32);
}
.drawer__panel {
  position: absolute;
  top: 0;
  right: 0;
  bottom: 0;
  display: flex;
  flex-direction: column;
  max-width: 92vw;
  background: var(--c-surface);
  box-shadow: var(--sh-3);
  outline: none;
}
.drawer__head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: var(--sp-3);
  padding: var(--sp-4) var(--sp-5);
  border-bottom: 1px solid var(--c-line);
}
.drawer__titles {
  min-width: 0;
}
.drawer__title {
  margin: 0;
  font-size: var(--fs-h3);
  line-height: var(--lh-h3);
  font-weight: 600;
  color: var(--c-ink-900);
}
.drawer__desc {
  margin: 2px 0 0;
  font-size: var(--fs-caption);
  line-height: var(--lh-caption);
  color: var(--c-ink-500);
}
.drawer__close {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 28px;
  height: 28px;
  flex: none;
  border: 1px solid transparent;
  border-radius: var(--r-md);
  background: transparent;
  color: var(--c-ink-500);
  cursor: pointer;
  transition: background-color var(--t-hover) var(--ease);
}
.drawer__close:hover {
  background: var(--c-line-soft);
  color: var(--c-ink-900);
}
.drawer__close:focus-visible {
  outline: none;
  box-shadow: var(--focus-ring);
}
.drawer__body {
  flex: 1;
  overflow-y: auto;
  padding: var(--sp-5);
}
.drawer__foot {
  display: flex;
  align-items: center;
  justify-content: flex-end;
  gap: var(--sp-2);
  padding: var(--sp-3) var(--sp-5);
  border-top: 1px solid var(--c-line);
  background: var(--c-surface);
}

.drawer-enter-active,
.drawer-leave-active {
  transition: opacity var(--t-panel) var(--ease);
}
.drawer-enter-active .drawer__panel,
.drawer-leave-active .drawer__panel {
  transition: transform var(--t-panel) var(--ease);
}
.drawer-enter-from,
.drawer-leave-to {
  opacity: 0;
}
.drawer-enter-from .drawer__panel,
.drawer-leave-to .drawer__panel {
  transform: translateX(100%);
}
@media (prefers-reduced-motion: reduce) {
  .drawer-enter-active,
  .drawer-leave-active,
  .drawer-enter-active .drawer__panel,
  .drawer-leave-active .drawer__panel {
    transition: none;
  }
}
</style>
