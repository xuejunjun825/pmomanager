<script setup lang="ts">
/**
 * 已选 @ 成员（DESIGN §1.3 / §4）：24 高 pill，头像 16 + 姓名 12 + ×。
 * 只承载「结构化提及」——手打 @ 姓名 不进入这里，也不授予可见性（AC-9）。
 */
import { initialOf } from '@/utils/format'
import type { UserOption } from '@/types/api'

withDefaults(
  defineProps<{
    users: UserOption[]
    disabled?: boolean
  }>(),
  { disabled: false },
)

const emit = defineEmits<{ remove: [id: number] }>()
</script>

<template>
  <ul v-if="users.length" class="chips" aria-label="已选择的成员">
    <li v-for="user in users" :key="user.id" class="chip">
      <span class="chip__avatar" aria-hidden="true">{{ initialOf(user.name) }}</span>
      <span class="chip__name">{{ user.name }}</span>
      <button
        type="button"
        class="chip__remove"
        :aria-label="`移除 ${user.name}`"
        :disabled="disabled"
        @click="emit('remove', user.id)"
      >
        ×
      </button>
    </li>
  </ul>
</template>

<style scoped>
.chips {
  display: flex;
  flex-wrap: wrap;
  gap: var(--sp-2);
  margin: 0 0 var(--sp-2);
  padding: 0;
  list-style: none;
}
.chip {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  height: 24px;
  padding: 0 var(--sp-2);
  border-radius: var(--r-pill);
  background: var(--c-primary-soft);
  color: var(--c-primary);
  font-size: var(--fs-caption);
  line-height: 16px;
  max-width: 100%;
}
.chip__avatar {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 16px;
  height: 16px;
  flex: none;
  border-radius: var(--r-pill);
  background: var(--c-surface);
  color: var(--c-primary);
  font-size: 9px;
  font-weight: 600;
  line-height: 1;
}
.chip__name {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.chip__remove {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 16px;
  height: 16px;
  padding: 0;
  border: 0;
  border-radius: var(--r-pill);
  background: none;
  color: var(--c-primary);
  font-family: inherit;
  font-size: 14px;
  line-height: 1;
  cursor: pointer;
}
.chip__remove:hover {
  background: var(--c-primary-border);
}
.chip__remove:disabled {
  color: var(--c-ink-400);
  cursor: not-allowed;
}
.chip__remove:focus-visible {
  outline: 2px solid var(--c-primary);
  outline-offset: 1px;
}
</style>
