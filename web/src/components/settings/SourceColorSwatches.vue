<script setup lang="ts">
/**
 * 来源色板单选（DESIGN §3.2 / §5）：8 个 32×32 色块，radiogroup 语义 + 方向键切换。
 * - 色值只来自后端 palette（SourcePalette），组件不写死 hex；
 * - 色名必须与色块同时出现（DESIGN §1.2），下方「颜色：墨蓝」保证不靠色觉；
 * - 选中 = 2px 主色外环 + 1px 白内描边 + 白色 ✓。
 */
import { computed, nextTick, ref } from 'vue'
import type { SourcePaletteEntry } from '@/types/api'

const props = withDefaults(
  defineProps<{
    modelValue: string
    options: SourcePaletteEntry[]
    /**
     * radiogroup 的无障碍名称。同一份色板被来源与主题两个弹窗复用，
     * 读屏若恒念「来源颜色」会把上下文说错；默认值保持既有来源弹窗的口径。
     */
    label?: string
  }>(),
  { label: '来源颜色' },
)

const emit = defineEmits<{ 'update:modelValue': [value: string] }>()

const swatchRefs = ref<HTMLButtonElement[]>([])

const selectedIndex = computed(() =>
  props.options.findIndex((entry) => entry.color.toLowerCase() === (props.modelValue ?? '').toLowerCase()),
)
const selectedName = computed(() => props.options[selectedIndex.value]?.name ?? '')

function isSelected(color: string): boolean {
  return color.toLowerCase() === (props.modelValue ?? '').toLowerCase()
}

function select(index: number): void {
  const entry = props.options[index]
  if (!entry) return
  emit('update:modelValue', entry.color)
}

/** 方向键移动：先落值再把焦点交给新选中的色块，读屏读到的是新颜色 */
async function moveTo(index: number): Promise<void> {
  if (!props.options.length) return
  const next = (index + props.options.length) % props.options.length
  select(next)
  await nextTick()
  swatchRefs.value[next]?.focus()
}

function onKeydown(event: KeyboardEvent, index: number): void {
  if (!['ArrowRight', 'ArrowDown', 'ArrowLeft', 'ArrowUp', 'Home', 'End'].includes(event.key)) return
  event.preventDefault()
  if (event.key === 'Home') void moveTo(0)
  else if (event.key === 'End') void moveTo(props.options.length - 1)
  else if (event.key === 'ArrowRight' || event.key === 'ArrowDown') void moveTo(index + 1)
  else void moveTo(index - 1)
}

/** roving tabindex：始终只有当前选中项（无选中时是第一项）进 Tab 序列 */
function tabIndexAt(index: number): number {
  if (selectedIndex.value === -1) return index === 0 ? 0 : -1
  return index === selectedIndex.value ? 0 : -1
}
</script>

<template>
  <div class="swatches">
    <div v-if="options.length" class="swatches__row" role="radiogroup" :aria-label="label">
      <button
        v-for="(entry, index) in options"
        :key="entry.color"
        ref="swatchRefs"
        type="button"
        class="swatch"
        :class="{ 'is-selected': isSelected(entry.color) }"
        :style="{ backgroundColor: entry.color }"
        role="radio"
        :aria-checked="isSelected(entry.color)"
        :aria-label="`颜色：${entry.name}`"
        :tabindex="tabIndexAt(index)"
        @click="select(index)"
        @keydown="onKeydown($event, index)"
      >
        <svg
          v-if="isSelected(entry.color)"
          class="swatch__check"
          width="12"
          height="12"
          viewBox="0 0 12 12"
          aria-hidden="true"
        >
          <path
            d="M2.5 6.2 5 8.7l4.5-5.4"
            fill="none"
            stroke="#ffffff"
            stroke-width="1.8"
            stroke-linecap="round"
            stroke-linejoin="round"
          />
        </svg>
      </button>
    </div>
    <p v-else class="swatches__empty">色板暂不可用，请刷新后重试</p>
    <p class="swatches__name">颜色：{{ selectedName || '未选择' }}</p>
  </div>
</template>

<style scoped>
.swatches {
  display: flex;
  flex-direction: column;
  gap: var(--sp-2);
}
.swatches__row {
  display: flex;
  flex-wrap: wrap;
  gap: var(--sp-3);
  /* 选中外环 + 白描边会向外扩 4px，留出空间避免被卡片裁掉 */
  padding: 4px 5px;
  margin: -4px -5px;
}
.swatch {
  position: relative;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 32px;
  height: 32px;
  padding: 0;
  border: 1px solid var(--c-line);
  border-radius: var(--r-md);
  cursor: pointer;
  outline: 2px solid transparent;
  outline-offset: 2px;
  transition:
    outline-color var(--t-hover) var(--ease),
    border-color var(--t-hover) var(--ease);
}
.swatch:hover:not(.is-selected) {
  outline-color: var(--c-ink-400);
}
.swatch.is-selected {
  outline: 2px solid var(--c-primary);
  border-color: transparent;
  box-shadow: inset 0 0 0 1px #ffffff;
}
.swatch:focus-visible {
  outline: 2px solid var(--c-primary);
  outline-offset: 2px;
}
.swatch__check {
  display: block;
  filter: drop-shadow(0 1px 1px rgba(15, 23, 42, 0.35));
}
.swatches__name {
  margin: 0;
  font-size: var(--fs-caption);
  line-height: var(--lh-caption);
  color: var(--c-ink-500);
}
.swatches__empty {
  margin: 0;
  font-size: var(--fs-caption);
  color: var(--c-danger-text);
}
</style>
