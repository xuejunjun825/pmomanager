<script setup lang="ts">
/**
 * 任务池表格（DESIGN §4.2）：8 个数据列 + 24px 箭头列 = 964px，整行可点，不设操作列。
 * 逾期只读服务端字段 is_overdue / overdue_days / due_soon —— 这里没有任何日期推算（AC-11/AC-17）。
 */
import { computed } from 'vue'
import DataTable, { type TableColumn } from '@/components/ui/DataTable.vue'
import StatusBadge from '@/components/ui/StatusBadge.vue'
import OverduePill from '@/components/ui/OverduePill.vue'
import OwnerChip from '@/components/ui/OwnerChip.vue'
import SourceTag from '@/components/ui/SourceTag.vue'
import EmptyState from '@/components/ui/EmptyState.vue'
import { formatDate, formatDateTime } from '@/utils/format'
import { isFinishedStatus } from '@/types/enums'
import type { TaskRow } from '@/types/api'

const props = withDefaults(
  defineProps<{
    items: TaskRow[]
    loading?: boolean
    /** 刚创建的任务：1200ms 高亮（DESIGN §4.2） */
    highlightId?: number | null
    emptyTitle?: string
    emptyHint?: string
  }>(),
  { loading: false, highlightId: null, emptyTitle: '没有符合条件的任务', emptyHint: '调整筛选条件后再试' },
)

defineEmits<{ 'row-click': [row: TaskRow, index: number] }>()

const columns: TableColumn[] = [
  { key: 'title', label: '标题', width: '200px' },
  { key: 'owner', label: '负责人', width: '104px' },
  { key: 'dueDate', label: '截止日期', width: '128px' },
  { key: 'source', label: '来源', width: '150px' },
  { key: 'status', label: '状态', width: '88px' },
  { key: 'overdue', label: '逾期', width: '96px' },
  { key: 'urgeCount', label: '催办', width: '64px', numeric: true },
  { key: 'updatedAt', label: '最近更新', width: '110px' },
]

const rows = computed<TaskRow[]>(() => (Array.isArray(props.items) ? props.items : []))
/** DataTable 的泛型约束是 Record<string, unknown>，接口类型过不去；只在这一处降级，插槽里立刻收窄 */
const tableRows = computed(() => rows.value as unknown as Record<string, unknown>[])

/** DataTable 的插槽行是 Record<string, unknown>（其泛型约束不含接口索引签名），这里只做一次收窄 */
function asTask(row: Record<string, unknown>): TaskRow {
  return row as unknown as TaskRow
}

function finished(row: TaskRow): boolean {
  return isFinishedStatus(row.status)
}

function sourceText(row: TaskRow): string {
  const source = row.source
  if (!source) return '—'
  // 形态以「是否绑定会议」为准，不按来源编码判断：自定义会议型来源编码不是 MEETING（D-01 / AC-5）
  if (source.meetingId != null) {
    return source.meetingTitle || source.note || '会议决议'
  }
  return source.note || '—'
}

function isHighlighted(row: TaskRow): boolean {
  return props.highlightId !== null && row.id === props.highlightId
}
</script>

<template>
  <DataTable
    class="task-table"
    :columns="columns"
    :rows="tableRows"
    :loading="loading"
    clickable
    row-key="id"
    @row-click="(row, index) => $emit('row-click', asTask(row), index)"
  >
    <template #cell-title="{ row }">
      <div class="cell-title" :class="{ 'is-highlight': isHighlighted(asTask(row)) }">
        <span v-if="asTask(row).isOverdue" class="cell-title__bar" aria-hidden="true" />
        <span class="cell-title__text" :class="{ 'is-finished': finished(asTask(row)) }" :title="asTask(row).title">
          {{ asTask(row).title }}
        </span>
      </div>
    </template>

    <template #cell-owner="{ row }">
      <OwnerChip
        :name="asTask(row).owner?.name"
        size="sm"
        :muted="finished(asTask(row))"
      />
    </template>

    <template #cell-dueDate="{ row }">
      <span class="num cell-text" :class="{ 'is-finished': finished(asTask(row)) }">
        {{ formatDate(asTask(row).dueDate) }}
      </span>
    </template>

    <template #cell-source="{ row }">
      <span class="cell-source">
        <SourceTag
          :code="asTask(row).source?.type ?? ''"
          :name="asTask(row).source?.typeLabel"
          :color="asTask(row).source?.color"
          :soft-color="asTask(row).source?.softColor"
          :border-color="asTask(row).source?.borderColor"
        />
        <span class="cell-text cell-source__text" :class="{ 'is-finished': finished(asTask(row)) }">
          {{ sourceText(asTask(row)) }}
        </span>
      </span>
    </template>

    <template #cell-status="{ row }">
      <StatusBadge :status="asTask(row).status" size="sm" />
    </template>

    <template #cell-overdue="{ row }">
      <OverduePill v-if="asTask(row).isOverdue" :days="asTask(row).overdueDays" />
      <OverduePill
        v-else-if="asTask(row).dueSoon && !finished(asTask(row))"
        variant="due-soon"
      />
      <span v-else class="cell-dash">—</span>
    </template>

    <template #cell-urgeCount="{ row }">
      <span class="num cell-text" :class="{ 'is-finished': finished(asTask(row)) }">
        {{ asTask(row).urgeCount > 0 ? asTask(row).urgeCount : '—' }}
      </span>
    </template>

    <template #cell-updatedAt="{ row }">
      <span class="num cell-text cell-text--muted" :title="asTask(row).updatedAt">
        {{ formatDateTime(asTask(row).updatedAt) }}
      </span>
    </template>

    <template #empty>
      <slot name="empty">
        <EmptyState :title="emptyTitle" :description="emptyHint" size="sm" />
      </slot>
    </template>
  </DataTable>
</template>

<style scoped>
/* 逾期行的左侧红条：td 做定位容器，插槽里的绝对定位元素贴住行首（DataTable 不支持行级 class） */
.task-table :deep(tbody td:first-child) {
  position: relative;
}
.task-table :deep(tbody tr:has(.is-highlight) td) {
  animation: row-highlight 1200ms var(--ease) both;
}
@keyframes row-highlight {
  0%,
  55% {
    background: var(--c-primary-soft);
  }
  100% {
    background: transparent;
  }
}
.cell-title {
  position: relative;
  display: flex;
  align-items: center;
  min-width: 0;
}
.cell-title__bar {
  position: absolute;
  left: calc(var(--sp-3) * -1);
  top: 50%;
  width: 3px;
  height: 24px;
  margin-top: -12px;
  background: var(--c-danger);
  border-radius: 0 var(--r-sm) var(--r-sm) 0;
}
.cell-title__text {
  display: block;
  overflow: hidden;
  white-space: nowrap;
  text-overflow: ellipsis;
  font-weight: 500;
  color: var(--c-ink-900);
}
.cell-source {
  display: flex;
  align-items: center;
  gap: var(--sp-2);
  min-width: 0;
}
.cell-source__text {
  overflow: hidden;
  white-space: nowrap;
  text-overflow: ellipsis;
}
.cell-text {
  color: var(--c-ink-700);
}
.cell-text--muted {
  color: var(--c-ink-500);
}
.is-finished {
  color: var(--c-ink-600);
}
.cell-dash {
  color: var(--c-ink-400);
}
</style>
