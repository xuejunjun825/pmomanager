<script setup lang="ts">
/**
 * 任务事件时间线（DESIGN §4.5 ④）：最新在上，原因以引用块展示（打回/取消/改派/改期必填）。
 * 事件由服务端写入，前端只排序与映射文案，不改写业务含义。
 */
import { computed } from 'vue'
import Timeline, { type TimelineItem } from '@/components/ui/Timeline.vue'
import { formatDateTime } from '@/utils/format'
import { TASK_STATUS_LABELS } from '@/types/enums'
import type { TaskEvent } from '@/types/api'

const props = defineProps<{ events: TaskEvent[] }>()

const items = computed<TimelineItem[]>(() => {
  const list = Array.isArray(props.events) ? [...props.events] : []
  return list
    .sort((a, b) => Date.parse(b.createdAt) - Date.parse(a.createdAt))
    .map((event, index) => {
      const label = event.actionLabel || event.action
      const tone = /打回|取消/.test(label)
        ? 'danger'
        : /验收通过|完成/.test(label)
          ? 'success'
          : 'default'
      const flow =
        event.fromStatus && event.toStatus
          ? `${TASK_STATUS_LABELS[event.fromStatus]} → ${TASK_STATUS_LABELS[event.toStatus]}`
          : ''
      return {
        key: `${event.createdAt}-${index}`,
        title: label,
        actor: event.actor?.name,
        time: formatDateTime(event.createdAt),
        reason: event.reason ?? undefined,
        meta: flow || undefined,
        tone,
      } as TimelineItem
    })
})
</script>

<template>
  <Timeline :items="items" empty-text="还没有流转记录" />
</template>
