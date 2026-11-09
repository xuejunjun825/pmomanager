<script setup lang="ts">
/** 来源标签（DESIGN §5.6）：20 高 / 11px；三种来源色固定，便于扫列。 */
import { computed } from 'vue'
import { SOURCE_TYPE_LABELS, type SourceType } from '@/types/enums'

const props = defineProps<{ type: SourceType; meetingTitle?: string | null }>()

const label = computed(() => SOURCE_TYPE_LABELS[props.type])
const title = computed(() => (props.meetingTitle ? `${label.value}：${props.meetingTitle}` : label.value))
</script>

<template>
  <span class="tag" :class="`tag--${type.toLowerCase()}`" :title="title">
    {{ label }}
  </span>
</template>

<style scoped>
.tag {
  display: inline-flex;
  align-items: center;
  height: 20px;
  padding: 0 6px;
  border: 1px solid transparent;
  border-radius: var(--r-sm);
  font-size: var(--fs-micro);
  line-height: var(--lh-micro);
  font-weight: 500;
  white-space: nowrap;
}
.tag--meeting {
  background: var(--c-primary-soft);
  border-color: var(--c-primary-border);
  color: var(--c-primary);
}
.tag--project {
  background: var(--c-purple-soft);
  border-color: #d9d6fe;
  color: var(--c-purple);
}
.tag--daily {
  background: var(--c-line-soft);
  border-color: var(--c-line);
  color: var(--c-ink-600);
}
</style>
