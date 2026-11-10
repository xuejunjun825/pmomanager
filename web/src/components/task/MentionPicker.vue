<script setup lang="ts">
/**
 * @ 成员选择器（DESIGN §3.5 / §7）。
 *
 * 两条纪律：
 * 1. 只有在这里被选中的成员才产生结构化 mentions——正文里手打「@姓名」不授予可见性、
 *    不发通知（AC-9），所以本组件是提及 id 的唯一来源；
 * 2. 名单只取启用账号并排除自己，上限 10 人；达到上限后其余行禁用而不是消失。
 */
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { initialOf } from '@/utils/format'
import type { UserOption } from '@/types/api'

const props = withDefaults(
  defineProps<{
    modelValue: number[]
    users: UserOption[]
    selfId?: number
    /** 最多可选人数，默认 10（与后端校验同值） */
    max?: number
  }>(),
  { max: 10 },
)

const emit = defineEmits<{
  'update:modelValue': [ids: number[]]
  close: []
  /** 新选中一位成员：调用方据此在光标处补 @姓名（取消选中不回删正文） */
  pick: [user: UserOption]
}>()

interface MentionUser extends UserOption {
  username?: string
}

const root = ref<HTMLElement | null>(null)
const searchRef = ref<HTMLInputElement | null>(null)
const keyword = ref('')
const activeIndex = ref(0)

const selectedIds = computed(() => new Set(props.modelValue))
const full = computed(() => props.modelValue.length >= props.max)

/** 只列启用账号，排除自己（后端 /users/options 默认已排除停用，这里再兜一次） */
const roster = computed<MentionUser[]>(() => {
  const list = Array.isArray(props.users) ? props.users : []
  return list
    .filter((user) => user.status !== 'DISABLED' && user.id !== props.selfId)
    .slice()
    .sort((a, b) => a.name.localeCompare(b.name, 'zh-Hans-CN'))
})

const filtered = computed<MentionUser[]>(() => {
  const query = keyword.value.trim().toLowerCase()
  if (!query) return roster.value
  return roster.value.filter((user) => {
    const account = user.username ?? ''
    return user.name.toLowerCase().includes(query) || account.toLowerCase().includes(query)
  })
})

const activeOption = computed(() => filtered.value[activeIndex.value])

watch(filtered, () => {
  if (activeIndex.value > filtered.value.length - 1) {
    activeIndex.value = Math.max(0, filtered.value.length - 1)
  }
})

function isSelected(user: MentionUser): boolean {
  return selectedIds.value.has(user.id)
}

function toggle(user: MentionUser): void {
  const ids = [...props.modelValue]
  const index = ids.indexOf(user.id)
  if (index >= 0) {
    ids.splice(index, 1)
    emit('update:modelValue', ids)
    return
  }
  if (full.value) return
  ids.push(user.id)
  emit('update:modelValue', ids)
  emit('pick', user)
}

function onSearchKeydown(event: KeyboardEvent): void {
  switch (event.key) {
    case 'ArrowDown':
      event.preventDefault()
      activeIndex.value = Math.min(filtered.value.length - 1, activeIndex.value + 1)
      break
    case 'ArrowUp':
      event.preventDefault()
      activeIndex.value = Math.max(0, activeIndex.value - 1)
      break
    case 'Enter': {
      event.preventDefault()
      const user = activeOption.value
      if (user) toggle(user)
      break
    }
    case 'Escape':
      event.preventDefault()
      emit('close')
      break
    case 'Tab':
      emit('close')
      break
    default:
      break
  }
}

function onDocumentMouseDown(event: MouseEvent): void {
  if (!root.value) return
  if (!root.value.contains(event.target as Node)) emit('close')
}

onMounted(() => {
  document.addEventListener('mousedown', onDocumentMouseDown)
  void nextTick(() => searchRef.value?.focus())
})

onBeforeUnmount(() => {
  document.removeEventListener('mousedown', onDocumentMouseDown)
})
</script>

<template>
  <div ref="root" class="picker" role="dialog" aria-label="选择要 @ 的成员">
    <div class="picker__search">
      <input
        ref="searchRef"
        v-model="keyword"
        type="search"
        class="picker__input"
        placeholder="搜索姓名或账号"
        role="combobox"
        aria-label="搜索成员"
        aria-controls="mention-listbox"
        :aria-expanded="true"
        :aria-activedescendant="activeOption ? `mention-option-${activeOption.id}` : undefined"
        autocomplete="off"
        @keydown="onSearchKeydown"
      />
    </div>

    <ul id="mention-listbox" class="picker__list" role="listbox" aria-label="成员名单">
      <li v-if="!filtered.length" class="picker__empty" role="presentation">
        {{ roster.length ? '没有匹配的成员' : '暂无可 @ 的成员' }}
      </li>
      <li v-for="(user, index) in filtered" :key="user.id" role="presentation">
        <button
          :id="`mention-option-${user.id}`"
          type="button"
          class="picker__option"
          :class="{
            'is-selected': isSelected(user),
            'is-active': index === activeIndex,
          }"
          role="option"
          :aria-selected="isSelected(user)"
          :disabled="!isSelected(user) && full"
          @click="toggle(user)"
          @mouseenter="activeIndex = index"
        >
          <span class="picker__avatar" aria-hidden="true">{{ initialOf(user.name) }}</span>
          <span class="picker__name">{{ user.name }}</span>
          <span v-if="user.username" class="picker__account">{{ user.username }}</span>
          <span v-if="isSelected(user)" class="picker__check" aria-hidden="true">✓</span>
        </button>
      </li>
    </ul>

    <div class="picker__footer">
      <span class="picker__count" aria-live="polite">
        {{ full ? `${max} 人已选满，取消一位后可继续` : `已选 ${modelValue.length}/${max}` }}
      </span>
      <span class="picker__hint">Esc 关闭</span>
    </div>
  </div>
</template>

<style scoped>
.picker {
  position: absolute;
  left: 0;
  top: calc(100% + 6px);
  z-index: 30;
  display: flex;
  flex-direction: column;
  width: 320px;
  max-height: 320px;
  border: 1px solid var(--c-line);
  border-radius: var(--r-lg);
  background: var(--c-surface);
  box-shadow: var(--sh-2);
  animation: picker-in 180ms var(--ease);
  overflow: hidden;
}
.picker__search {
  flex: none;
  padding: var(--sp-2);
  border-bottom: 1px solid var(--c-line-soft);
}
.picker__input {
  width: 100%;
  height: 32px;
  padding: 0 var(--sp-2);
  border: 1px solid var(--c-line);
  border-radius: var(--r-sm);
  background: var(--c-surface);
  color: var(--c-ink-900);
  font-family: inherit;
  font-size: var(--fs-body-sm);
}
.picker__input:focus {
  border-color: var(--c-primary);
  outline: none;
  box-shadow: var(--focus-ring);
}
.picker__list {
  flex: 1 1 auto;
  margin: 0;
  padding: var(--sp-1) 0;
  list-style: none;
  overflow-y: auto;
}
.picker__empty {
  padding: var(--sp-4);
  color: var(--c-ink-500);
  font-size: var(--fs-body-sm);
  text-align: center;
}
.picker__option {
  display: flex;
  align-items: center;
  gap: var(--sp-2);
  width: 100%;
  height: 40px;
  padding: 0 var(--sp-3);
  border: 0;
  background: none;
  color: var(--c-ink-900);
  font-family: inherit;
  font-size: var(--fs-body-sm);
  text-align: left;
  cursor: pointer;
}
.picker__option.is-active:not(:disabled) {
  background: var(--c-surface-hover);
}
.picker__option.is-selected {
  background: var(--c-primary-soft);
  color: var(--c-primary);
}
.picker__option:disabled {
  color: var(--c-ink-400);
  cursor: not-allowed;
}
.picker__option:focus-visible {
  outline: 2px solid var(--c-primary);
  outline-offset: -2px;
}
.picker__avatar {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 24px;
  height: 24px;
  flex: none;
  border-radius: var(--r-pill);
  background: var(--c-line-soft);
  color: var(--c-ink-700);
  font-size: var(--fs-micro);
  font-weight: 600;
}
.picker__option.is-selected .picker__avatar {
  background: var(--c-surface);
  color: var(--c-primary);
}
.picker__name {
  flex: none;
  max-width: 120px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.picker__account {
  flex: 1 1 auto;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  color: var(--c-ink-500);
  font-size: var(--fs-caption);
}
.picker__check {
  flex: none;
  width: 16px;
  text-align: center;
  font-size: var(--fs-caption);
  font-weight: 600;
}
.picker__footer {
  flex: none;
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--sp-2);
  height: 40px;
  padding: 0 var(--sp-3);
  border-top: 1px solid var(--c-line-soft);
  background: var(--c-surface);
  font-size: var(--fs-caption);
  color: var(--c-ink-500);
}
.picker__count {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.picker__hint {
  flex: none;
  color: var(--c-ink-400);
}

@keyframes picker-in {
  from {
    opacity: 0;
    transform: translateY(4px);
  }
  to {
    opacity: 1;
    transform: translateY(0);
  }
}

@media (prefers-reduced-motion: reduce) {
  .picker {
    animation: none;
  }
}

@media (max-width: 767px) {
  .picker {
    position: fixed;
    left: 0;
    right: 0;
    bottom: 0;
    top: auto;
    width: auto;
    max-height: 70vh;
    padding-bottom: env(safe-area-inset-bottom, 0);
    border-radius: var(--r-xl) var(--r-xl) 0 0;
    border-bottom: 0;
  }
  .picker__option {
    height: 44px;
  }
}
</style>
