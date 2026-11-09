<script setup lang="ts">
/** 分页（DESIGN §5.9）：总数在前，页码在右；每页 10/20/50。 */
import { computed } from 'vue'
import AppButton from './AppButton.vue'

const props = withDefaults(
  defineProps<{
    page: number
    size: number
    total: number
    sizeOptions?: number[]
  }>(),
  { sizeOptions: () => [10, 20, 50] },
)

const emit = defineEmits<{ 'update:page': [page: number]; 'update:size': [size: number] }>()

const totalPages = computed(() => Math.max(1, Math.ceil(props.total / Math.max(1, props.size))))
const from = computed(() => (props.total === 0 ? 0 : (props.page - 1) * props.size + 1))
const to = computed(() => Math.min(props.total, props.page * props.size))
const canPrev = computed(() => props.page > 1)
const canNext = computed(() => props.page < totalPages.value)

function go(page: number): void {
  const next = Math.min(totalPages.value, Math.max(1, page))
  if (next !== props.page) emit('update:page', next)
}

function changeSize(event: Event): void {
  const size = Number((event.target as HTMLSelectElement).value)
  if (!Number.isNaN(size) && size !== props.size) emit('update:size', size)
}
</script>

<template>
  <div class="pager" role="navigation" aria-label="分页">
    <p class="pager__summary">
      共 <span class="num">{{ total }}</span> 条<span class="pager__range">· {{ from }}–{{ to }}</span>
    </p>
    <div class="pager__controls">
      <label class="pager__size">
        <span class="visually-hidden">每页条数</span>
        <select :value="size" @change="changeSize">
          <option v-for="option in sizeOptions" :key="option" :value="option">{{ option }} 条/页</option>
        </select>
      </label>
      <AppButton size="compact" variant="secondary" :disabled="!canPrev" aria-label="上一页" @click="go(page - 1)">
        上一页
      </AppButton>
      <span class="pager__page"><span class="num">{{ page }}</span> / <span class="num">{{ totalPages }}</span></span>
      <AppButton size="compact" variant="secondary" :disabled="!canNext" aria-label="下一页" @click="go(page + 1)">
        下一页
      </AppButton>
    </div>
  </div>
</template>

<style scoped>
.pager {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--sp-4);
  padding: var(--sp-3) 0 0;
  flex-wrap: wrap;
}
.pager__summary {
  margin: 0;
  font-size: var(--fs-body-sm);
  color: var(--c-ink-600);
}
.pager__range {
  color: var(--c-ink-400);
  margin-left: 4px;
}
.pager__controls {
  display: flex;
  align-items: center;
  gap: var(--sp-2);
}
.pager__size select {
  height: 28px;
  padding: 0 var(--sp-2);
  border: 1px solid var(--c-line);
  border-radius: var(--r-md);
  background: var(--c-surface);
  color: var(--c-ink-600);
  font-family: inherit;
  font-size: var(--fs-caption);
  cursor: pointer;
}
.pager__size select:focus-visible {
  outline: none;
  border-color: var(--c-primary);
  box-shadow: var(--focus-ring);
}
.pager__page {
  font-size: var(--fs-caption);
  color: var(--c-ink-500);
  min-width: 44px;
  text-align: center;
}
</style>
