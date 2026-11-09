<script setup lang="ts">
/**
 * 通用时间线（DESIGN §5.9）：8px 节点 + 1px 左线，最新在上。
 * 只负责排布；动作词、操作人、时间、原因都由调用方给（任务事件、通知记录都复用它）。
 */
export interface TimelineItem {
  key: string | number
  title: string
  actor?: string
  time?: string
  /** 原因/备注，渲染为引用块（打回、取消、改派必须留痕） */
  reason?: string
  meta?: string
  tone?: 'default' | 'danger' | 'success'
}

withDefaults(
  defineProps<{
    items: TimelineItem[]
    emptyText?: string
    dense?: boolean
  }>(),
  { emptyText: '暂无记录', dense: false },
)
</script>

<template>
  <ol class="timeline" :class="{ 'timeline--dense': dense }">
    <li v-if="!items.length" class="timeline__empty text-caption">{{ emptyText }}</li>
    <li
      v-for="item in items"
      :key="item.key"
      class="timeline__item"
      :class="`is-${item.tone ?? 'default'}`"
    >
      <span class="timeline__node" aria-hidden="true" />
      <div class="timeline__body">
        <div class="timeline__head">
          <span class="timeline__title">{{ item.title }}</span>
          <span v-if="item.actor" class="timeline__actor">{{ item.actor }}</span>
          <span v-if="item.time" class="timeline__time num">{{ item.time }}</span>
        </div>
        <div v-if="item.meta" class="timeline__meta">{{ item.meta }}</div>
        <blockquote v-if="item.reason" class="timeline__reason">{{ item.reason }}</blockquote>
      </div>
    </li>
  </ol>
</template>

<style scoped>
.timeline {
  margin: 0;
  padding: 0;
  list-style: none;
}
.timeline__item {
  position: relative;
  padding: 0 0 var(--sp-4) var(--sp-5);
}
.timeline__item:last-child {
  padding-bottom: 0;
}
.timeline__item::before {
  content: '';
  position: absolute;
  left: 3px;
  top: 12px;
  bottom: 0;
  width: 1px;
  background: var(--c-line);
}
.timeline__item:last-child::before {
  display: none;
}
.timeline__node {
  position: absolute;
  left: 0;
  top: 6px;
  width: 8px;
  height: 8px;
  border-radius: var(--r-pill);
  background: var(--c-surface);
  border: 1px solid var(--c-ink-400);
}
.timeline__item.is-danger .timeline__node {
  border-color: var(--c-danger);
  background: var(--c-danger);
}
.timeline__item.is-success .timeline__node {
  border-color: var(--c-success);
  background: var(--c-success);
}
.timeline__body {
  min-width: 0;
}
.timeline__head {
  display: flex;
  align-items: baseline;
  gap: var(--sp-2);
  flex-wrap: wrap;
}
.timeline__title {
  font-size: var(--fs-body-sm);
  font-weight: 600;
  color: var(--c-ink-900);
}
.timeline__actor {
  font-size: var(--fs-caption);
  color: var(--c-ink-600);
}
.timeline__time {
  font-size: var(--fs-caption);
  color: var(--c-ink-500);
}
.timeline__meta {
  margin-top: 2px;
  font-size: var(--fs-caption);
  color: var(--c-ink-500);
}
.timeline__reason {
  margin: var(--sp-2) 0 0;
  padding: var(--sp-2) var(--sp-3);
  border-left: 2px solid var(--c-line);
  background: var(--c-surface-hover);
  border-radius: 0 var(--r-sm) var(--r-sm) 0;
  font-size: var(--fs-body-sm);
  line-height: var(--lh-body-sm);
  color: var(--c-ink-700);
  white-space: pre-wrap;
  overflow-wrap: anywhere;
}
.timeline--dense .timeline__item {
  padding-bottom: var(--sp-3);
}
.timeline__empty {
  padding: var(--sp-2) 0;
}
</style>
