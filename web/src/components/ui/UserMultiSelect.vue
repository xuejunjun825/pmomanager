<script setup lang="ts">
/**
 * 人员多选（DESIGN §4.5）：chips + 搜索，会议新建 / 编辑共用这一套控件。
 *
 * - 受控组件：只 emit `update:modelValue`（number[]），不持有选中态；
 * - 打开后输入框获得焦点，↑↓/Home/End 移动、Enter 选中/取消、Esc 关闭、Backspace 在空输入时删最后一个；
 * - chips 用「名称 + ×」，超过 120px 省略并保留 title；停用用户不可选（选项 disabled）。
 */
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import type { UserOption } from '@/types/api'

const props = withDefaults(
  defineProps<{
    modelValue: number[]
    options: UserOption[]
    placeholder?: string
    invalid?: boolean
    error?: string
    disabled?: boolean
  }>(),
  { placeholder: '搜索并选择人员', invalid: false, disabled: false },
)

const emit = defineEmits<{ 'update:modelValue': [value: number[]] }>()

const rootRef = ref<HTMLElement | null>(null)
const inputRef = ref<HTMLInputElement | null>(null)
const open = ref(false)
const query = ref('')
const activeIndex = ref(-1)

const fallbackId = `multi-${Math.random().toString(36).slice(2, 9)}`
const inputId = computed(() => fallbackId)

const selectedIds = computed<number[]>(() => (Array.isArray(props.modelValue) ? props.modelValue : []))
const selectedSet = computed(() => new Set(selectedIds.value))

const selectedUsers = computed(() =>
  selectedIds.value.map((id) => {
    const found = (Array.isArray(props.options) ? props.options : []).find((item) => item.id === id)
    return { id, name: found?.name ?? `用户 #${id}`, disabled: found?.status === 'DISABLED' }
  }),
)

interface Candidate {
  id: number
  name: string
  disabled: boolean
}

/** 停用用户保留在列表里但不可选；已选中的不再重复出现在候选里（避免两处状态） */
const candidates = computed<Candidate[]>(() => {
  const keyword = query.value.trim().toLowerCase()
  return (Array.isArray(props.options) ? props.options : [])
    .filter((item) => !selectedSet.value.has(item.id))
    .filter((item) => (keyword ? item.name.toLowerCase().includes(keyword) : true))
    .map((item) => ({ id: item.id, name: item.name, disabled: item.status === 'DISABLED' }))
})

const activeDescendant = computed(() =>
  activeIndex.value >= 0 && candidates.value[activeIndex.value]
    ? `multi-option-${candidates.value[activeIndex.value].id}`
    : undefined,
)

function firstEnabledIndex(list: Candidate[]): number {
  return list.findIndex((item) => !item.disabled)
}

function openDropdown(): void {
  if (props.disabled) return
  open.value = true
  query.value = ''
  void nextTick(() => {
    activeIndex.value = firstEnabledIndex(candidates.value)
    inputRef.value?.focus()
  })
}

function closeDropdown(): void {
  open.value = false
  query.value = ''
  activeIndex.value = -1
}

function toggle(user: Candidate): void {
  if (user.disabled) return
  const next = selectedSet.value.has(user.id)
    ? selectedIds.value.filter((id) => id !== user.id)
    : [...selectedIds.value, user.id]
  emit('update:modelValue', next)
  query.value = ''
  void nextTick(() => {
    activeIndex.value = firstEnabledIndex(candidates.value)
  })
}

function remove(id: number): void {
  if (props.disabled) return
  emit(
    'update:modelValue',
    selectedIds.value.filter((item) => item !== id),
  )
}

function move(delta: number): void {
  const list = candidates.value
  if (!list.length) return
  let index = activeIndex.value
  for (let step = 0; step < list.length; step += 1) {
    index = (index + delta + list.length) % list.length
    if (!list[index].disabled) {
      activeIndex.value = index
      return
    }
  }
}

function moveEdge(edge: 'first' | 'last'): void {
  const list = candidates.value
  if (!list.length) return
  if (edge === 'first') {
    activeIndex.value = firstEnabledIndex(list)
  } else {
    for (let index = list.length - 1; index >= 0; index -= 1) {
      if (!list[index].disabled) {
        activeIndex.value = index
        return
      }
    }
  }
}

function commitActive(): void {
  const picked = candidates.value[activeIndex.value]
  if (picked) toggle(picked)
}

function onKeydown(event: KeyboardEvent): void {
  if (props.disabled) return
  if (!open.value) {
    if (['Enter', ' ', 'ArrowDown', 'ArrowUp'].includes(event.key)) {
      event.preventDefault()
      openDropdown()
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
    case 'Backspace':
      if (query.value === '' && selectedIds.value.length) {
        event.preventDefault()
        remove(selectedIds.value[selectedIds.value.length - 1])
      }
      break
    case 'Tab':
      closeDropdown()
      break
    default:
      break
  }
}

function onInput(event: Event): void {
  query.value = (event.target as HTMLInputElement).value
  activeIndex.value = firstEnabledIndex(candidates.value)
  if (!open.value) open.value = true
}

/** 点容器空白处也要打开（不是只有输入框） */
function onControlMousedown(event: MouseEvent): void {
  if (props.disabled) return
  if (open.value) return
  event.preventDefault()
  openDropdown()
}

function onDocumentMousedown(event: MouseEvent): void {
  if (!open.value) return
  const target = event.target as Node | null
  if (rootRef.value && target && !rootRef.value.contains(target)) closeDropdown()
}

function onFocusOut(event: FocusEvent): void {
  const next = event.relatedTarget as Node | null
  if (rootRef.value && next && rootRef.value.contains(next)) return
  closeDropdown()
}

watch(
  () => props.disabled,
  (disabled) => {
    if (disabled) closeDropdown()
  },
)

onMounted(() => document.addEventListener('mousedown', onDocumentMousedown))
onBeforeUnmount(() => document.removeEventListener('mousedown', onDocumentMousedown))
</script>

<template>
  <div
    ref="rootRef"
    class="multi"
    :class="{ 'is-open': open, 'is-invalid': invalid || !!error, 'is-disabled': disabled }"
    @focusout="onFocusOut"
  >
    <div
      class="multi__control"
      role="combobox"
      :aria-expanded="open"
      aria-haspopup="listbox"
      aria-multiselectable="true"
      :aria-controls="`${inputId}-listbox`"
      :aria-owns="`${inputId}-listbox`"
      :aria-describedby="error ? `${inputId}-error` : undefined"
      :aria-invalid="invalid || !!error || undefined"
      @mousedown="onControlMousedown"
    >
      <span v-for="user in selectedUsers" :key="user.id" class="multi__chip" :title="user.name">
        <span class="multi__chip-text">{{ user.name }}</span>
        <button
          type="button"
          class="multi__chip-x"
          :disabled="disabled"
          :aria-label="`移除 ${user.name}`"
          @mousedown.stop.prevent
          @click.stop="remove(user.id)"
        >
          ×
        </button>
      </span>
      <input
        :id="inputId"
        ref="inputRef"
        class="multi__input"
        type="text"
        role="textbox"
        autocomplete="off"
        :value="query"
        :placeholder="selectedUsers.length ? '继续添加' : placeholder"
        :disabled="disabled"
        :aria-autocomplete="'list'"
        :aria-activedescendant="activeDescendant"
        @input="onInput"
        @keydown="onKeydown"
      />
    </div>

    <Transition name="multi-drop">
      <div v-if="open" class="multi__panel">
        <ul :id="`${inputId}-listbox`" class="multi__list" role="listbox" aria-label="人员">
          <li
            v-for="(user, index) in candidates"
            :id="`multi-option-${user.id}`"
            :key="user.id"
            class="multi__option"
            :class="{ 'is-active': index === activeIndex, 'is-disabled': user.disabled }"
            role="option"
            :aria-selected="false"
            :aria-disabled="user.disabled || undefined"
            @mousedown.prevent="toggle(user)"
          >
            <span class="multi__option-name">{{ user.name }}</span>
            <span v-if="user.disabled" class="multi__option-note">已停用</span>
            <span v-else class="multi__option-plus" aria-hidden="true">+</span>
          </li>
        </ul>
        <p v-if="!candidates.length" class="multi__empty">
          {{ query.trim() ? '没有匹配的用户' : '没有可选的人员' }}
        </p>
      </div>
    </Transition>

    <p v-if="error" :id="`${inputId}-error`" class="multi__error" role="alert">{{ error }}</p>
  </div>
</template>

<style scoped>
.multi {
  position: relative;
  display: flex;
  flex-direction: column;
  gap: var(--sp-1);
}
.multi__control {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 4px;
  box-sizing: border-box;
  min-height: 36px;
  padding: 4px 6px;
  border: 1px solid var(--c-line);
  border-radius: var(--r-md);
  background: var(--c-surface);
  cursor: text;
  transition: border-color var(--t-fast) var(--ease), box-shadow var(--t-fast) var(--ease);
}
.multi__control:hover {
  border-color: var(--c-ink-400);
}
.multi.is-open .multi__control,
.multi__control:focus-within {
  border-color: var(--c-primary);
  box-shadow: var(--focus-ring);
}
.multi.is-invalid .multi__control {
  border-color: var(--c-danger-border);
}
.multi.is-disabled .multi__control {
  background: var(--c-line-soft);
  cursor: not-allowed;
}
.multi__chip {
  display: inline-flex;
  align-items: center;
  gap: 2px;
  box-sizing: border-box;
  max-width: 120px;
  height: 24px;
  padding: 0 2px 0 6px;
  border: 1px solid var(--c-primary-border);
  border-radius: var(--r-sm);
  background: var(--c-primary-soft);
  color: var(--c-primary);
  font-size: var(--fs-caption);
  line-height: var(--lh-caption);
}
.multi__chip-text {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.multi__chip-x {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 20px;
  height: 20px;
  padding: 0;
  border: 0;
  border-radius: var(--r-xs);
  background: transparent;
  color: inherit;
  font-size: 14px;
  line-height: 1;
  cursor: pointer;
}
.multi__chip-x:hover {
  background: var(--c-primary-border);
}
.multi__chip-x:disabled {
  cursor: not-allowed;
}
.multi__input {
  flex: 1 1 96px;
  min-width: 96px;
  height: 24px;
  padding: 0 4px;
  border: 0;
  outline: none;
  background: transparent;
  color: var(--c-ink-900);
  font-family: inherit;
  font-size: var(--fs-body-sm);
}
.multi__input::placeholder {
  color: var(--c-ink-400);
}
.multi__panel {
  position: absolute;
  top: calc(100% + 4px);
  left: 0;
  z-index: var(--z-dropdown);
  box-sizing: border-box;
  width: 100%;
  max-height: 264px;
  overflow-y: auto;
  padding: 4px 0;
  border: 1px solid var(--c-line);
  border-radius: var(--r-lg);
  background: var(--c-surface);
  box-shadow: var(--sh-2);
}
.multi__list {
  margin: 0;
  padding: 0;
  list-style: none;
}
.multi__option {
  display: flex;
  align-items: center;
  gap: var(--sp-2);
  box-sizing: border-box;
  height: 32px;
  padding: 0 var(--sp-3);
  font-size: var(--fs-body-sm);
  color: var(--c-ink-700);
  cursor: pointer;
}
.multi__option:hover {
  background: var(--c-surface-hover);
}
.multi__option.is-active {
  box-shadow: inset 2px 0 var(--c-primary);
  background: var(--c-surface-hover);
}
.multi__option.is-disabled {
  color: var(--c-ink-400);
  cursor: not-allowed;
}
.multi__option-name {
  flex: 1;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.multi__option-note {
  font-size: var(--fs-micro);
  color: var(--c-ink-400);
}
.multi__option-plus {
  color: var(--c-ink-400);
  font-size: 14px;
}
.multi__empty {
  margin: 0;
  padding: var(--sp-3);
  font-size: var(--fs-caption);
  color: var(--c-ink-500);
}
.multi__error {
  margin: 0;
  font-size: var(--fs-caption);
  line-height: var(--lh-caption);
  color: var(--c-danger-text);
}
.multi-drop-enter-active,
.multi-drop-leave-active {
  transition: opacity var(--t-fast) var(--ease), transform var(--t-fast) var(--ease);
}
.multi-drop-enter-from,
.multi-drop-leave-to {
  opacity: 0;
  transform: translateY(-2px);
}
</style>
