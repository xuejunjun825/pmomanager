<script setup lang="ts">
/** 错误状态（DESIGN §5.11）：说明 + 重试；不暴露技术堆栈，只给一句人话。 */
import AppButton from './AppButton.vue'

withDefaults(
  defineProps<{
    title?: string
    description?: string
    retryLabel?: string
    /** 展示 traceId 便于运维定位（不展示异常栈） */
    traceId?: string
  }>(),
  { title: '加载失败', description: '数据暂时取不到，请重试。', retryLabel: '重试' },
)

const emit = defineEmits<{ retry: [] }>()
</script>

<template>
  <div class="error-state" role="alert">
    <svg width="40" height="40" viewBox="0 0 40 40" aria-hidden="true" class="error-state__icon">
      <circle cx="20" cy="20" r="14" fill="none" stroke="currentColor" stroke-width="1.5" />
      <path d="M20 13v9" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" />
      <circle cx="20" cy="27" r="1.2" fill="currentColor" />
    </svg>
    <p class="error-state__title">{{ title }}</p>
    <p class="error-state__desc">{{ description }}</p>
    <p v-if="traceId" class="error-state__trace">问题编号 {{ traceId }}</p>
    <AppButton variant="secondary" size="default" @click="emit('retry')">{{ retryLabel }}</AppButton>
  </div>
</template>

<style scoped>
.error-state {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: var(--sp-2);
  padding: var(--sp-10) var(--sp-6);
  text-align: center;
}
.error-state__icon {
  color: var(--c-ink-400);
}
.error-state__title {
  margin: var(--sp-2) 0 0;
  font-size: var(--fs-body);
  font-weight: 600;
  color: var(--c-ink-900);
}
.error-state__desc {
  margin: 0 0 var(--sp-2);
  max-width: 360px;
  font-size: var(--fs-body-sm);
  line-height: var(--lh-body-sm);
  color: var(--c-ink-500);
}
.error-state__trace {
  margin: 0 0 var(--sp-2);
  font-family: var(--font-num);
  font-size: var(--fs-micro);
  color: var(--c-ink-400);
}
</style>
