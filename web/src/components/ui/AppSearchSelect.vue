<script setup lang="ts">
/**
 * 可搜索单选下拉（DESIGN §3.5 / TECHNICAL §4.3）：只用于项目场景。
 *
 * 200 个项目用原生 `<select>` 选不动，所以新增 combobox + listbox 控件；
 * 来源 / 会议 / 负责人（≤30 项）继续用 `AppSelect`，不为一个场景替换全局控件。
 *
 * - 触发器本身是搜索框：关闭态 `readonly` 显示已选名称，打开后可直接输入过滤；
 * - 搜索只做「trim 后大小写不敏感子串」，不做拼音/首字母（不做就不承诺）；
 * - 受控组件：不做内部选中态，选中只 emit，值由 `modelValue` 决定；
 * - 键盘：↑↓ / Home / End / Enter / Esc / Tab；停用项键盘跳过，`aria-disabled` 标记。
 */
import { computed, nextTick, onBeforeUnmount, onMounted, ref, useAttrs } from 'vue'
import { UNASSIGNED_VALUE } from '@/utils/projectOptions'

export interface SearchSelectOption {
  label: string
  value: string
  /** 停用项：可见可读、标记 aria-disabled、键盘跳过、点击无效 */
  disabled?: boolean
  /** 自由文本候选（allowCustom）：无匹配时尾部生成，值即原文 */
  custom?: boolean
}

const props = withDefaults(
  defineProps<{
    modelValue?: string | null
    options: SearchSelectOption[]
    label?: string
    placeholder?: string
    size?: 'compact' | 'default' | 'large'
    disabled?: boolean
    invalid?: boolean
    error?: string
    hint?: string
    required?: boolean
    id?: string
    emptyText?: string
    /** meta 未到时显示 3 行骨架，不显示「没有匹配」（DESIGN §1.4） */
    loading?: boolean
    /**
     * 允许自由文本（DESIGN §3.4）：无匹配时列表尾部给「使用「X」作为…（非系统用户）」，
     * 落库为原文。默认 false，既有项目场景零变化。
     */
    allowCustom?: boolean
  }>(),
  {
    size: 'default',
    disabled: false,
    invalid: false,
    required: false,
    loading: false,
    placeholder: '请选择',
    emptyText: '没有匹配的项目',
    allowCustom: false,
  },
)

const emit = defineEmits<{ 'update:modelValue': [value: string]; change: [value: string] }>()

/**
 * class/style 落在根节点（页面用它定宽），其余属性（如 aria-label）落到 combobox 输入框，
 * 否则 label 会挂在普通 div 上，读屏读不到控件。
 */
defineOptions({ inheritAttrs: false })
const attrs = useAttrs()
const rootAttrs = computed(() => {
  const out: Record<string, unknown> = {}
  if (attrs.class !== undefined) out.class = attrs.class
  if (attrs.style !== undefined) out.style = attrs.style
  return out
})
const inputAttrs = computed(() => {
  const out: Record<string, unknown> = {}
  for (const key of Object.keys(attrs)) {
    if (key !== 'class' && key !== 'style') out[key] = attrs[key]
  }
  return out
})

const fallbackId = `search-sel-${Math.random().toString(36).slice(2, 9)}`
const fieldId = computed(() => props.id || fallbackId)
const listboxId = computed(() => `${fieldId.value}-listbox`)

const open = ref(false)
const query = ref('')
const activeIndex = ref(-1)
/** 用户是否用方向键移动过高亮：Tab 只在「明确导航过」时提交，避免路过筛选条就改了条件 */
const navigated = ref(false)
const flipRight = ref(false)
const rootRef = ref<HTMLElement | null>(null)
const inputRef = ref<HTMLInputElement | null>(null)

const selectedValue = computed(() => props.modelValue ?? '')
const selectedOption = computed(() => props.options.find((item) => item.value === selectedValue.value))
/**
 * 关闭态显示：命中选项用选项名；allowCustom 且当前值是列表外的原文时，
 * 显示「值（非系统用户）」，历史主持人文本零迁移也不会看起来像丢了。
 */
const displayLabel = computed(() => {
  if (selectedOption.value) return selectedOption.value.label
  if (props.allowCustom && selectedValue.value.trim()) return `${selectedValue.value}（非系统用户）`
  return ''
})

/** 输入原文且与任何选项名称都不完全相等时才生成自由文本候选 */
const customQuery = computed(() => {
  if (!props.allowCustom) return ''
  const keyword = query.value.trim()
  if (!keyword) return ''
  const exact = props.options.some((item) => item.label.trim().toLowerCase() === keyword.toLowerCase())
  return exact ? '' : keyword
})

const filteredOptions = computed<SearchSelectOption[]>(() => {
  const keyword = query.value.trim().toLowerCase()
  const base = !keyword
    ? props.options
    : props.options.filter((item) => item.label.toLowerCase().includes(keyword))
  if (!customQuery.value) return base
  return [
    ...base,
    {
      label: `使用「${customQuery.value}」作为主持人（非系统用户）`,
      value: customQuery.value,
      custom: true,
    },
  ]
})

/** 底栏计数只数真实项目：排除「全部项目」（''）、「未指定项目」（none）与自由文本候选 */
const totalCount = computed(
  () =>
    props.options.filter(
      (item) => item.value !== '' && item.value !== UNASSIGNED_VALUE && !item.custom,
    ).length,
)
const visibleCount = computed(
  () =>
    filteredOptions.value.filter(
      (item) => item.value !== '' && item.value !== UNASSIGNED_VALUE && !item.custom,
    ).length,
)

const activeDescendant = computed(() =>
  open.value && activeIndex.value >= 0 ? optionId(activeIndex.value) : undefined,
)

function optionId(index: number): string {
  return `${listboxId.value}-opt-${index}`
}

function firstEnabledIndex(): number {
  return filteredOptions.value.findIndex((item) => !item.disabled)
}

function measureFlip(): void {
  const rect = rootRef.value?.getBoundingClientRect()
  if (!rect) return
  const panelWidth = Math.min(320, Math.max(240, rect.width))
  flipRight.value = rect.left + panelWidth > window.innerWidth - 8
}

async function scrollActiveIntoView(): Promise<void> {
  await nextTick()
  if (activeIndex.value < 0) return
  const element = document.getElementById(optionId(activeIndex.value))
  element?.scrollIntoView?.({ block: 'nearest' })
}

async function openDropdown(initialQuery = ''): Promise<void> {
  if (props.disabled || open.value) return
  // allowCustom：当前值是列表外原文时，打开即带原文，便于直接回车确认或改写
  query.value =
    initialQuery || (props.allowCustom && !selectedOption.value ? selectedValue.value : '')
  navigated.value = false
  activeIndex.value = -1
  open.value = true
  await nextTick()
  measureFlip()
  inputRef.value?.focus()
  const current = filteredOptions.value.findIndex(
    (item) => item.value === selectedValue.value && !item.disabled,
  )
  activeIndex.value = current >= 0 ? current : firstEnabledIndex()
  void scrollActiveIntoView()
}

function closeDropdown(refocus = true): void {
  if (!open.value) return
  open.value = false
  query.value = ''
  activeIndex.value = -1
  navigated.value = false
  if (refocus) void nextTick(() => inputRef.value?.focus())
}

function selectOption(option: SearchSelectOption, refocus = true): void {
  if (option.disabled) return
  emit('update:modelValue', option.value)
  emit('change', option.value)
  closeDropdown(refocus)
}

function commitActive(refocus = true): void {
  const option = filteredOptions.value[activeIndex.value]
  if (!option || option.disabled) return
  selectOption(option, refocus)
}

function move(delta: number): void {
  const list = filteredOptions.value
  if (!list.length) return
  let index = activeIndex.value
  if (index < 0) index = delta > 0 ? -1 : list.length
  let next = index + delta
  while (next >= 0 && next < list.length && list[next].disabled) next += delta
  if (next < 0 || next >= list.length) return
  activeIndex.value = next
  navigated.value = true
  void scrollActiveIntoView()
}

function moveEdge(edge: 'first' | 'last'): void {
  const list = filteredOptions.value
  if (!list.length) return
  const step = edge === 'first' ? 1 : -1
  let index = edge === 'first' ? 0 : list.length - 1
  while (index >= 0 && index < list.length && list[index].disabled) index += step
  if (index < 0 || index >= list.length) return
  activeIndex.value = index
  navigated.value = true
  void scrollActiveIntoView()
}

function onControlMousedown(event: MouseEvent): void {
  if (props.disabled) {
    event.preventDefault()
    return
  }
  // 关闭态点触发器 = 打开；打开态放行默认行为，允许在搜索框里点选光标
  if (!open.value) {
    event.preventDefault()
    void openDropdown()
  }
}

function onInput(event: Event): void {
  if (!open.value) return
  query.value = (event.target as HTMLInputElement).value
  navigated.value = false
  activeIndex.value = firstEnabledIndex()
}

function onKeydown(event: KeyboardEvent): void {
  if (props.disabled) return
  if (event.isComposing) return

  if (!open.value) {
    if (event.key === 'Enter' || event.key === ' ' || event.key === 'ArrowDown' || event.key === 'ArrowUp') {
      event.preventDefault()
      void openDropdown()
      return
    }
    // 直接键入即开始搜索（触发器关闭态是 readonly，字符由 query 承载）
    if (event.key.length === 1 && !event.ctrlKey && !event.metaKey && !event.altKey) {
      event.preventDefault()
      void openDropdown(event.key)
    }
    return
  }

  switch (event.key) {
    case 'ArrowDown':
      event.preventDefault()
      move(1)
      break
    case 'ArrowUp':
      event.preventDefault()
      move(-1)
      break
    case 'Home':
      event.preventDefault()
      moveEdge('first')
      break
    case 'End':
      event.preventDefault()
      moveEdge('last')
      break
    case 'Enter':
      event.preventDefault()
      commitActive()
      break
    case 'Escape':
      event.preventDefault()
      closeDropdown()
      break
    case 'Tab':
      // 技术方案要求「Tab 提交当前高亮」，但路过筛选条不应意外改条件：
      // 只在用户用方向键明确导航过时提交，否则仅关闭。
      if (navigated.value) commitActive(false)
      closeDropdown(false)
      break
    default:
      break
  }
}

function clearSearch(): void {
  query.value = ''
  navigated.value = false
  activeIndex.value = firstEnabledIndex()
  inputRef.value?.focus()
}

function onDocumentMousedown(event: MouseEvent): void {
  if (!open.value) return
  const root = rootRef.value
  if (root && event.target instanceof Node && root.contains(event.target)) return
  closeDropdown(false)
}

function onWindowResize(): void {
  if (open.value) measureFlip()
}

onMounted(() => {
  document.addEventListener('mousedown', onDocumentMousedown)
  window.addEventListener('resize', onWindowResize)
})

onBeforeUnmount(() => {
  document.removeEventListener('mousedown', onDocumentMousedown)
  window.removeEventListener('resize', onWindowResize)
})
</script>

<template>
  <div ref="rootRef" class="field" :class="`field--${size}`" v-bind="rootAttrs">
    <label v-if="label" class="field__label" :for="fieldId">
      {{ label }}<span v-if="required" class="field__req" aria-hidden="true">*</span>
    </label>

    <div
      class="field__control"
      :class="{ 'is-open': open, 'is-invalid': invalid || !!error, 'is-disabled': disabled }"
      @mousedown="onControlMousedown"
    >
      <input
        ref="inputRef"
        v-bind="inputAttrs"
        :id="fieldId"
        class="field__input"
        type="text"
        role="combobox"
        autocomplete="off"
        aria-autocomplete="list"
        aria-haspopup="listbox"
        :aria-expanded="open"
        :aria-controls="listboxId"
        :aria-activedescendant="activeDescendant"
        :aria-invalid="invalid || !!error || undefined"
        :value="open ? query : displayLabel"
        :placeholder="placeholder"
        :readonly="!open"
        :disabled="disabled"
        @input="onInput"
        @keydown="onKeydown"
      />
      <svg class="field__chevron" width="12" height="12" viewBox="0 0 12 12" aria-hidden="true">
        <path d="M2.5 4.5 6 8l3.5-3.5" fill="none" stroke="currentColor" stroke-width="1.4" stroke-linecap="round" stroke-linejoin="round" />
      </svg>
    </div>

    <Transition name="drop">
      <div v-if="open" class="panel" :class="{ 'panel--flip': flipRight }">
        <div v-if="loading" class="skeletons" aria-hidden="true">
          <span v-for="n in 3" :key="n" class="skeleton" />
        </div>
        <ul v-show="!loading && filteredOptions.length > 0" :id="listboxId" class="list" role="listbox" :aria-label="label || '项目'">
          <li
            v-for="(option, index) in filteredOptions"
            :id="optionId(index)"
            :key="option.value"
            class="option"
            role="option"
            :class="{
              'is-active': index === activeIndex,
              'is-selected': option.value === selectedValue,
              'is-disabled': option.disabled,
              'is-custom': option.custom,
            }"
            :aria-selected="option.value === selectedValue"
            :aria-disabled="option.disabled ? 'true' : undefined"
            @click="selectOption(option)"
          >
            <span class="option__text" :title="option.label">{{ option.label }}</span>
            <span v-if="option.value === selectedValue" class="option__check" aria-hidden="true">✓</span>
          </li>
        </ul>
        <div v-if="!loading && filteredOptions.length === 0" class="empty">
          <span class="empty__text">{{ emptyText }}</span>
          <button v-if="query" type="button" class="empty__clear" @click="clearSearch">清除搜索</button>
        </div>
        <div v-if="!loading" class="panel__footer" aria-live="polite">
          <template v-if="allowCustom">共 {{ totalCount }} 项 · 显示 {{ visibleCount }} 项</template>
          <template v-else>共 {{ totalCount }} 个项目 · 显示 {{ visibleCount }} 个</template>
        </div>
      </div>
    </Transition>

    <p v-if="error" class="field__error" role="alert">{{ error }}</p>
    <p v-else-if="hint" class="field__hint">{{ hint }}</p>
  </div>
</template>

<style scoped>
.field {
  position: relative;
  display: flex;
  flex-direction: column;
  gap: var(--sp-1);
  min-width: 0;
}
.field__label {
  font-size: var(--fs-body-sm);
  font-weight: 500;
  color: var(--c-ink-700);
  line-height: var(--lh-body-sm);
}
.field__req {
  color: var(--c-danger);
  margin-left: 2px;
}
.field__control {
  display: flex;
  align-items: center;
  height: 32px;
  padding: 0 var(--sp-2) 0 var(--sp-3);
  background: var(--c-surface);
  border: 1px solid var(--c-line);
  border-radius: var(--r-md);
  cursor: pointer;
  transition:
    border-color var(--t-hover) var(--ease),
    box-shadow var(--t-hover) var(--ease);
}
.field--compact .field__control {
  height: 28px;
}
.field--large .field__control {
  height: 40px;
}
.field__control:hover:not(.is-disabled) {
  border-color: var(--c-ink-400);
}
.field__control:focus-within {
  border-color: var(--c-primary);
  box-shadow: var(--focus-ring);
}
.field__control.is-open {
  border-color: var(--c-primary);
  box-shadow: var(--focus-ring);
}
.field__control.is-invalid {
  border-color: var(--c-danger);
}
.field__control.is-disabled {
  background: var(--c-line-soft);
  cursor: not-allowed;
}
.field__input {
  flex: 1;
  min-width: 0;
  height: 100%;
  border: 0;
  outline: none;
  background: transparent;
  color: var(--c-ink-900);
  font-family: inherit;
  font-size: var(--fs-body-sm);
  line-height: var(--lh-body-sm);
  text-overflow: ellipsis;
  cursor: inherit;
}
.field__input::placeholder {
  color: var(--c-ink-400);
}
.field__input:read-only {
  cursor: pointer;
}
.field--large .field__input {
  font-size: var(--fs-body);
}
.field__input:disabled {
  color: var(--c-ink-500);
  cursor: not-allowed;
}
.field__chevron {
  flex: none;
  color: var(--c-ink-400);
  transition: transform var(--t-hover) var(--ease);
}
.field__control.is-open .field__chevron {
  transform: rotate(180deg);
}

.panel {
  position: absolute;
  top: calc(100% + 4px);
  left: 0;
  z-index: var(--z-dropdown);
  display: flex;
  flex-direction: column;
  box-sizing: border-box;
  width: clamp(240px, 100%, 320px);
  max-height: 296px;
  overflow: hidden;
  padding: 4px 0;
  background: var(--c-surface);
  border: 1px solid var(--c-line);
  border-radius: var(--r-lg);
  box-shadow: var(--sh-2);
}
.panel--flip {
  left: auto;
  right: 0;
}
.list {
  flex: 1;
  min-height: 0;
  margin: 0;
  padding: 0;
  overflow-y: auto;
  list-style: none;
}
.option {
  display: flex;
  align-items: center;
  gap: var(--sp-2);
  height: 32px;
  padding: 0 var(--sp-3);
  color: var(--c-ink-900);
  font-size: var(--fs-body-sm);
  line-height: var(--lh-body-sm);
  cursor: pointer;
}
.option:hover {
  background: var(--c-surface-hover);
}
.option.is-active {
  background: var(--c-surface-hover);
  box-shadow: inset 2px 0 0 var(--c-primary);
}
.option.is-selected {
  background: var(--c-primary-soft);
  color: var(--c-primary);
}
/* 自由文本候选：与真实选项拉开层次，避免被误认为系统用户 */
.option.is-custom {
  border-top: 1px dashed var(--c-line);
  color: var(--c-ink-700);
  font-style: normal;
}
.option.is-custom .option__text {
  color: var(--c-primary);
}
.option__text {
  flex: 1;
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.option__check {
  flex: none;
  font-size: 12px;
  color: var(--c-primary);
}
.option.is-disabled {
  color: var(--c-ink-400);
  cursor: not-allowed;
}
.option.is-disabled:hover {
  background: transparent;
}
.skeletons {
  display: flex;
  flex-direction: column;
  gap: var(--sp-2);
  padding: var(--sp-2) var(--sp-3);
}
.skeleton {
  height: 32px;
  border-radius: var(--r-sm);
  background: var(--c-line-soft);
}
.empty {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--sp-2);
  padding: var(--sp-3);
  color: var(--c-ink-500);
  font-size: var(--fs-body-sm);
}
.empty__clear {
  border: 0;
  padding: 0;
  background: transparent;
  color: var(--c-primary);
  font: inherit;
  font-size: var(--fs-caption);
  cursor: pointer;
}
.empty__clear:hover {
  text-decoration: underline;
}
.panel__footer {
  padding: 6px var(--sp-3);
  border-top: 1px solid var(--c-line-soft);
  color: var(--c-ink-400);
  font-size: var(--fs-caption);
  line-height: var(--lh-caption);
}

.drop-enter-active,
.drop-leave-active {
  transition:
    opacity 180ms var(--ease),
    transform 180ms var(--ease);
}
.drop-enter-from,
.drop-leave-to {
  opacity: 0;
  transform: translateY(-8px);
}

.field__error {
  margin: 0;
  font-size: var(--fs-caption);
  line-height: var(--lh-caption);
  color: var(--c-danger-text);
}
.field__hint {
  margin: 0;
  font-size: var(--fs-caption);
  line-height: var(--lh-caption);
  color: var(--c-ink-500);
}

@media (prefers-reduced-motion: reduce) {
  .drop-enter-active,
  .drop-leave-active {
    transition: opacity 100ms linear;
  }
  .drop-enter-from,
  .drop-leave-to {
    transform: none;
  }
  .field__chevron {
    transition: none;
  }
}

@media (max-width: 768px) {
  .panel {
    max-height: 50vh;
  }
  .option {
    height: 40px;
  }
  .field--compact .field__control,
  .field__control {
    height: 40px;
  }
}
</style>
