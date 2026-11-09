<script setup lang="ts">
/** 表格骨架（DESIGN §5.13）：8 行 × N 列，行高 48，1.2s 微光。 */
withDefaults(defineProps<{ rows?: number; columns?: number }>(), { rows: 8, columns: 5 })
</script>

<template>
  <div class="skeleton" aria-busy="true" aria-live="polite">
    <span class="visually-hidden">正在加载</span>
    <div class="skeleton__head">
      <span v-for="c in columns" :key="`h-${c}`" class="sk-block skeleton__head-cell" />
    </div>
    <div v-for="r in rows" :key="`r-${r}`" class="skeleton__row">
      <span v-for="c in columns" :key="`c-${r}-${c}`" class="sk-block skeleton__cell" />
    </div>
  </div>
</template>

<style scoped>
.skeleton {
  border: 1px solid var(--c-line);
  border-radius: var(--r-lg);
  overflow: hidden;
  background: var(--c-surface);
}
.skeleton__head {
  display: grid;
  grid-auto-flow: column;
  grid-auto-columns: 1fr;
  gap: var(--sp-4);
  align-items: center;
  height: 40px;
  padding: 0 var(--sp-3);
  background: var(--c-surface-hover);
  border-bottom: 1px solid var(--c-line);
}
.skeleton__head-cell {
  height: 10px;
  border-radius: var(--r-sm);
}
.skeleton__row {
  display: grid;
  grid-auto-flow: column;
  grid-auto-columns: 1fr;
  gap: var(--sp-4);
  align-items: center;
  height: 48px;
  padding: 0 var(--sp-3);
  border-bottom: 1px solid var(--c-line-soft);
}
.skeleton__row:last-child {
  border-bottom: 0;
}
.skeleton__cell {
  height: 12px;
  border-radius: var(--r-sm);
}
</style>
