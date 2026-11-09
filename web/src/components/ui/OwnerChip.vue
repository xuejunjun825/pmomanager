<script setup lang="ts">
/** 负责人标识（DESIGN §5.7）：24 头像 + 13 姓名；无头像用姓名首字。 */
import { computed } from 'vue'
import { initialOf } from '@/utils/format'

const props = withDefaults(
  defineProps<{
    name?: string | null
    avatarUrl?: string | null
    size?: 'sm' | 'md'
    /** 停用账号整块降灰 */
    muted?: boolean
  }>(),
  { size: 'md', muted: false },
)

const initial = computed(() => initialOf(props.name))
const displayName = computed(() => props.name || '未指派')
</script>

<template>
  <span class="owner" :class="[{ 'owner--muted': muted }, `owner--${size}`]">
    <img v-if="avatarUrl" class="owner__avatar owner__avatar--img" :src="avatarUrl" :alt="displayName" />
    <span v-else class="owner__avatar" aria-hidden="true">{{ initial }}</span>
    <span class="owner__name" :title="displayName">{{ displayName }}</span>
  </span>
</template>

<style scoped>
.owner {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  min-width: 0;
}
.owner__avatar {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 24px;
  height: 24px;
  flex: none;
  border-radius: var(--r-pill);
  background: var(--c-primary-soft);
  border: 1px solid var(--c-primary-border);
  color: var(--c-primary);
  font-size: var(--fs-micro);
  font-weight: 600;
  line-height: 1;
  object-fit: cover;
}
.owner--sm .owner__avatar {
  width: 20px;
  height: 20px;
  font-size: 10px;
}
.owner__avatar--img {
  border-color: var(--c-line);
}
.owner__name {
  font-size: var(--fs-body-sm);
  line-height: var(--lh-body-sm);
  color: var(--c-ink-900);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.owner--sm .owner__name {
  font-size: var(--fs-caption);
}
.owner--muted .owner__avatar {
  background: var(--c-line-soft);
  border-color: var(--c-line);
  color: var(--c-ink-400);
}
.owner--muted .owner__name {
  color: var(--c-ink-500);
}
</style>
