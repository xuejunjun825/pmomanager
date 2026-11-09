<script setup lang="ts">
/** 空状态（DESIGN §5.10）：图标 48 + 标题 + 说明 + 一个主动作。 */
withDefaults(
  defineProps<{
    title: string
    description?: string
    size?: 'sm' | 'md'
  }>(),
  { description: '', size: 'md' },
)
</script>

<template>
  <div class="empty" :class="`empty--${size}`">
    <svg class="empty__icon" width="48" height="48" viewBox="0 0 48 48" aria-hidden="true">
      <rect x="8" y="14" width="32" height="24" rx="3" fill="none" stroke="currentColor" stroke-width="1.5" />
      <path d="M8 22h9l3 4h8l3-4h9" fill="none" stroke="currentColor" stroke-width="1.5" stroke-linejoin="round" />
    </svg>
    <p class="empty__title">{{ title }}</p>
    <p v-if="description" class="empty__desc">{{ description }}</p>
    <div v-if="$slots.default" class="empty__action"><slot /></div>
  </div>
</template>

<style scoped>
.empty {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: var(--sp-2);
  padding: var(--sp-10) var(--sp-6);
  text-align: center;
}
.empty--sm {
  padding: var(--sp-6) var(--sp-4);
  gap: var(--sp-1);
}
.empty__icon {
  color: var(--c-ink-400);
  margin-bottom: var(--sp-1);
}
.empty--sm .empty__icon {
  width: 32px;
  height: 32px;
}
.empty__title {
  margin: 0;
  font-size: var(--fs-body);
  font-weight: 600;
  color: var(--c-ink-700);
}
.empty--sm .empty__title {
  font-size: var(--fs-body-sm);
}
.empty__desc {
  margin: 0;
  max-width: 320px;
  font-size: var(--fs-body-sm);
  line-height: var(--lh-body-sm);
  color: var(--c-ink-500);
}
.empty--sm .empty__desc {
  font-size: var(--fs-caption);
  line-height: var(--lh-caption);
}
.empty__action {
  margin-top: var(--sp-2);
}
</style>
