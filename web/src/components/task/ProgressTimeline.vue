<script setup lang="ts">
/**
 * 「进度与流转」合并时间线（DESIGN §3.4 / §4）。
 *
 * 两条纪律：
 * 1. 进度与人写的正文，视觉层级低于状态徽标：无卡片、无边框、无底色，只共用一条竖线；
 * 2. 进度条目的 @ 高亮只认结构化 mentions——正文里手打的「@姓名」保持普通文本（AC-9）。
 *
 * 锚点：进度条目固定 id="progress-{id}" + tabindex="-1"，供通知深链定位与聚焦；
 * 定位后的 1600ms 高亮由页面加 `.is-anchor` 触发，样式留在这里（scoped）。
 */
import { computed } from 'vue'
import { formatDateTime, initialOf } from '@/utils/format'
import { TASK_STATUS_LABELS } from '@/types/enums'
import type { ProgressRow, TaskEvent } from '@/types/api'

const props = defineProps<{
  progress: ProgressRow[]
  events: TaskEvent[]
}>()

interface ProgressEntry {
  kind: 'progress'
  key: string
  at: number
  order: number
  row: ProgressRow
}

interface EventEntry {
  kind: 'event'
  key: string
  at: number
  order: number
  row: TaskEvent
}

type Entry = ProgressEntry | EventEntry

function timeOf(value?: string | null): number {
  const parsed = Date.parse(value ?? '')
  return Number.isFinite(parsed) ? parsed : 0
}

/** 统一按 createdAt 倒序；同一秒按 id 倒序（流转记录没有 id，按 0 处理） */
const merged = computed<Entry[]>(() => {
  const progress = Array.isArray(props.progress) ? props.progress : []
  const events = Array.isArray(props.events) ? props.events : []
  const list: Entry[] = [
    ...progress.map<ProgressEntry>((row) => ({
      kind: 'progress',
      key: `progress-${row.id}`,
      at: timeOf(row.createdAt),
      order: row.id,
      row,
    })),
    ...events.map<EventEntry>((row, index) => ({
      kind: 'event',
      key: `event-${index}-${row.createdAt}`,
      at: timeOf(row.createdAt),
      order: 0,
      row,
    })),
  ]
  return list.sort((a, b) => b.at - a.at || b.order - a.order)
})

function eventLabel(event: TaskEvent): string {
  return event.actionLabel || event.action
}

function eventTone(event: TaskEvent): 'default' | 'danger' | 'success' {
  const label = eventLabel(event)
  if (/打回|取消/.test(label)) return 'danger'
  if (/验收通过|完成/.test(label)) return 'success'
  return 'default'
}

function eventFlow(event: TaskEvent): string {
  if (!event.fromStatus || !event.toStatus) return ''
  return `${TASK_STATUS_LABELS[event.fromStatus]} → ${TASK_STATUS_LABELS[event.toStatus]}`
}

interface Segment {
  text: string
  mention: boolean
}

function escapeRegExp(value: string): string {
  return value.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')
}

/** 只高亮「选择器选中」的提及；同名多次出现一并高亮 */
function segments(row: ProgressRow): Segment[] {
  const content = row.content ?? ''
  const names = (row.mentions ?? []).map((user) => user.name).filter(Boolean)
  if (!names.length) return [{ text: content, mention: false }]
  const pattern = new RegExp(`@(${names.map(escapeRegExp).join('|')})`, 'g')
  const out: Segment[] = []
  let last = 0
  let match: RegExpExecArray | null
  while ((match = pattern.exec(content)) !== null) {
    if (match.index > last) out.push({ text: content.slice(last, match.index), mention: false })
    out.push({ text: match[0], mention: true })
    last = match.index + match[0].length
  }
  if (last < content.length) out.push({ text: content.slice(last), mention: false })
  return out.length ? out : [{ text: content, mention: false }]
}
</script>

<template>
  <ol class="tl" :class="{ 'tl--empty': !merged.length }">
    <li v-if="!merged.length" class="tl__empty">任务刚创建，还没有流转或进度记录</li>

    <template v-for="entry in merged" :key="entry.key">
      <!-- 进度：内容，不是系统事件 -->
      <li
        v-if="entry.kind === 'progress'"
        :id="`progress-${entry.row.id}`"
        class="tl__item tl__item--progress"
        tabindex="-1"
      >
        <span class="tl__node tl__node--progress" aria-hidden="true" />
        <article class="tl__body">
          <header class="tl__head">
            <span class="tl__avatar" aria-hidden="true">{{ initialOf(entry.row.author?.name) }}</span>
            <span class="tl__actor tl__actor--strong">{{ entry.row.author?.name || '未知成员' }}</span>
            <span class="tl__chip">进度</span>
            <span v-if="entry.row.mentionedMe" class="tl__for-me">提到了你</span>
            <time class="tl__time num" :datetime="entry.row.createdAt">
              {{ formatDateTime(entry.row.createdAt) }}
            </time>
          </header>
          <p class="tl__content">
            <template v-for="(segment, index) in segments(entry.row)" :key="`${entry.key}-${index}`">
              <span v-if="segment.mention" class="tl__mention">{{ segment.text }}</span>
              <template v-else>{{ segment.text }}</template>
            </template>
          </p>
        </article>
      </li>

      <!-- 流转：系统事件，沿用 r1 文案 -->
      <li v-else class="tl__item" :class="`is-${eventTone(entry.row)}`">
        <span class="tl__node" aria-hidden="true" />
        <div class="tl__body">
          <header class="tl__head">
            <span class="tl__title">{{ eventLabel(entry.row) }}</span>
            <span v-if="entry.row.actor" class="tl__actor">{{ entry.row.actor.name }}</span>
            <time class="tl__time num" :datetime="entry.row.createdAt">
              {{ formatDateTime(entry.row.createdAt) }}
            </time>
          </header>
          <div v-if="eventFlow(entry.row)" class="tl__meta">{{ eventFlow(entry.row) }}</div>
          <blockquote v-if="entry.row.reason" class="tl__reason">{{ entry.row.reason }}</blockquote>
        </div>
      </li>
    </template>
  </ol>
</template>

<style scoped>
.tl {
  margin: 0;
  padding: 0;
  list-style: none;
}
.tl__empty {
  color: var(--c-ink-500);
  font-size: var(--fs-caption);
}
.tl__item {
  position: relative;
  padding: 0 0 var(--sp-4) var(--sp-5);
  scroll-margin-top: 72px;
}
.tl__item:last-child {
  padding-bottom: 0;
}
.tl__item::before {
  content: '';
  position: absolute;
  left: 3px;
  top: 12px;
  bottom: 0;
  width: 1px;
  background: var(--c-line);
}
.tl__item:last-child::before {
  display: none;
}
.tl__node {
  position: absolute;
  left: 0;
  top: 6px;
  width: 8px;
  height: 8px;
  border-radius: 50%;
  border: 1px solid var(--c-ink-400);
  background: var(--c-surface);
}
.tl__node--progress {
  top: 8px;
  border-color: var(--c-primary);
  background: var(--c-primary);
}
.tl__item--progress .tl__head {
  min-height: 24px;
}
.tl__body {
  min-width: 0;
}
.tl__head {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: var(--sp-2);
}
.tl__title {
  color: var(--c-ink-900);
  font-size: var(--fs-body-sm);
  font-weight: 600;
}
.tl__actor {
  color: var(--c-ink-700);
  font-size: var(--fs-caption);
}
.tl__actor--strong {
  color: var(--c-ink-900);
  font-size: var(--fs-body-sm);
  font-weight: 600;
}
.tl__time {
  margin-left: auto;
  color: var(--c-ink-500);
  font-size: var(--fs-caption);
  white-space: nowrap;
}
.tl__avatar {
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
}
.tl__chip {
  display: inline-flex;
  align-items: center;
  height: 18px;
  padding: 0 5px;
  border-radius: var(--r-sm);
  background: var(--c-primary-soft);
  color: var(--c-primary);
  font-size: var(--fs-micro);
  font-weight: 500;
  line-height: 16px;
}
.tl__for-me {
  color: var(--c-primary);
  font-size: var(--fs-micro);
}
.tl__content {
  margin: 4px 0 0;
  color: var(--c-ink-900);
  font-size: var(--fs-body);
  line-height: 22px;
  white-space: pre-wrap;
  overflow-wrap: anywhere;
}
.tl__mention {
  color: var(--c-primary);
  text-decoration: underline;
  text-decoration-style: dotted;
  text-decoration-thickness: 1px;
  text-underline-offset: 2px;
}
.tl__meta {
  margin-top: 2px;
  color: var(--c-ink-500);
  font-size: var(--fs-caption);
}
.tl__reason {
  margin: var(--sp-2) 0 0;
  padding: var(--sp-2) var(--sp-3);
  border-left: 2px solid var(--c-line);
  color: var(--c-ink-700);
  font-size: var(--fs-caption);
  line-height: 1.7;
}
.tl__item.is-danger .tl__title {
  color: var(--c-danger-text);
}
.tl__item.is-success .tl__title {
  color: var(--c-success);
}

/* 通知深链定位：1600ms 一次性高亮，随后恢复透明 */
.tl__item.is-anchor {
  animation: anchor-highlight 1600ms var(--ease) both;
  border-radius: var(--r-sm);
}
.tl__item:focus-visible {
  outline: 2px solid var(--c-primary);
  outline-offset: 2px;
  border-radius: var(--r-sm);
}

@keyframes anchor-highlight {
  0%,
  72% {
    background-color: var(--hl-anchor);
  }
  100% {
    background-color: transparent;
  }
}

@media (prefers-reduced-motion: reduce) {
  .tl__item.is-anchor {
    animation: none;
    background-color: transparent;
  }
}
</style>
