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
import ProjectTag from '@/components/ui/ProjectTag.vue'
import ThemeTag from '@/components/ui/ThemeTag.vue'
import AppSelect from '@/components/ui/AppSelect.vue'
import EmptyState from '@/components/ui/EmptyState.vue'
import { formatDate, formatDateTime } from '@/utils/format'
import { isFinishedStatus } from '@/types/enums'
import { UNASSIGNED_VALUE, projectView } from '@/utils/projectOptions'
import { themeOptions as buildThemeOptions, themeView } from '@/utils/themeOptions'
import type { TaskRow, ThemeOptionRow } from '@/types/api'

const props = withDefaults(
  defineProps<{
    items: TaskRow[]
    loading?: boolean
    /** 刚创建的任务：1200ms 高亮（DESIGN §4.2） */
    highlightId?: number | null
    emptyTitle?: string
    emptyHint?: string
    /** meta.themes 全量（含已停用）：行内改主题的选项来源 */
    themes?: ThemeOptionRow[]
    /** 仅 ADMIN 可行内改主题；OWNER/EXEC 只看标签（AC-8） */
    editableTheme?: boolean
    /** 行内保存失败的行级文案：key=任务 id，失败回滚后仍显示在单元格下方 */
    themeErrors?: Record<number, string>
  }>(),
  {
    loading: false,
    highlightId: null,
    emptyTitle: '没有符合条件的任务',
    emptyHint: '调整筛选条件后再试',
    themes: () => [],
    editableTheme: false,
    themeErrors: () => ({}),
  },
)

const emit = defineEmits<{
  'row-click': [row: TaskRow, index: number]
  'theme-change': [row: TaskRow, value: string]
}>()

const columns: TableColumn[] = [
  { key: 'title', label: '标题', width: '200px' },
  { key: 'owner', label: '负责人', width: '104px' },
  { key: 'dueDate', label: '截止日期', width: '128px' },
  { key: 'source', label: '来源', width: '150px' },
  { key: 'theme', label: '主题', width: '96px' },
  { key: 'status', label: '状态', width: '88px' },
  { key: 'overdue', label: '逾期', width: '96px' },
  { key: 'urgeCount', label: '催办', width: '64px', numeric: true },
  { key: 'updatedAt', label: '最近更新', width: '110px' },
]

/** 行内主题选项：停用项保留在列表里（当前值可能就是停用项），未挂主题用 none 哨兵 */
const themeItems = computed(() =>
  buildThemeOptions(props.themes, {
    allOption: false,
    includeDisabled: true,
    includeUnassigned: true,
  }),
)

function themeValue(row: TaskRow): string {
  return row.theme?.id != null ? String(row.theme.id) : UNASSIGNED_VALUE
}

function themeProps(row: TaskRow) {
  return themeView(row.theme)
}

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
        <!-- 第二行：有项目位就渲染项目标签（含「未指定项目」），否则沿用会议名/说明（DESIGN §3.8） -->
        <ProjectTag
          v-if="projectView(asTask(row).source)"
          class="cell-source__project"
          v-bind="projectView(asTask(row).source) ?? {}"
        />
        <span
          v-else
          class="cell-text cell-source__text"
          :class="{ 'is-finished': finished(asTask(row)) }"
        >
          {{ sourceText(asTask(row)) }}
        </span>
      </span>
    </template>

    <template #cell-theme="{ row }">
      <!-- 单元格整体阻断冒泡：行内改主题不能顺手把用户带进详情页 -->
      <span class="cell-theme" @click.stop @mousedown.stop>
        <AppSelect
          v-if="editableTheme"
          class="cell-theme__select"
          :model-value="themeValue(asTask(row))"
          :options="themeItems"
          size="compact"
          :aria-label="`主题：${asTask(row).title}`"
          :invalid="Boolean(themeErrors[asTask(row).id])"
          @update:model-value="emit('theme-change', asTask(row), $event)"
        />
        <ThemeTag v-else v-bind="themeProps(asTask(row))" />
        <span v-if="themeErrors[asTask(row).id]" class="cell-theme__error" role="alert">
          {{ themeErrors[asTask(row).id] }}
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
/* 来源列两行：SourceTag（20）+ 项目标签/来源文本，行高 48 内上下各留 4（DESIGN §3.8） */
.cell-source {
  display: flex;
  flex-direction: column;
  align-items: flex-start;
  gap: 2px;
  min-width: 0;
}
.cell-source__text {
  max-width: 100%;
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
/* 主题列：只读给标签，ADMIN 给行内下拉；失败时行高从 48 长到 64 放 12px 红字（不整页刷新） */
.cell-theme {
  display: flex;
  flex-direction: column;
  align-items: flex-start;
  gap: 2px;
  min-width: 0;
}
.cell-theme__select {
  width: 100%;
}
.cell-theme__error {
  font-size: var(--fs-micro);
  line-height: var(--lh-micro);
  color: var(--c-danger-text);
}
</style>
