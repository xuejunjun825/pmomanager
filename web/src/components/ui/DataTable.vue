<script setup lang="ts" generic="T extends Record<string, unknown>">
/**
 * 台账表格（DESIGN §5.8）：表头 40 sticky / 行 48 / 数字右对齐 tabular-nums。
 * 表格只展示数据：逾期列用 OverduePill 插槽由页面给，这里不做任何日期推算。
 */
import { computed } from 'vue'
import EmptyState from './EmptyState.vue'

export interface TableColumn {
  key: string
  label: string
  width?: string
  align?: 'left' | 'right' | 'center'
  /** 数字列：右对齐 + 等宽数字 */
  numeric?: boolean
  /** 固定列宽时禁止收缩 */
  fixed?: boolean
}

const props = withDefaults(
  defineProps<{
    columns: TableColumn[]
    rows: T[]
    rowKey?: string | ((row: T) => string | number)
    loading?: boolean
    clickable?: boolean
    emptyTitle?: string
    emptyHint?: string
    /** 紧凑模式：行高 40（用于抽屉里的子表） */
    dense?: boolean
    /** 加载骨架行数：DESIGN §4.2 表格固定 8 行 */
    skeletonRows?: number
  }>(),
  {
    loading: false,
    clickable: false,
    dense: false,
    emptyTitle: '暂无数据',
    emptyHint: '',
    skeletonRows: 8,
  },
)

const emit = defineEmits<{ 'row-click': [row: T, index: number] }>()

function keyOf(row: T, index: number): string | number {
  if (typeof props.rowKey === 'function') return props.rowKey(row)
  if (typeof props.rowKey === 'string') {
    const value = row[props.rowKey]
    return typeof value === 'string' || typeof value === 'number' ? value : index
  }
  const id = row.id
  return typeof id === 'string' || typeof id === 'number' ? id : index
}

function alignOf(column: TableColumn): 'left' | 'right' | 'center' {
  return column.numeric ? 'right' : (column.align ?? 'left')
}

const colCount = computed(() => props.columns.length)
</script>

<template>
  <div class="table-wrap">
    <table class="table" :class="{ 'table--dense': dense, 'table--clickable': clickable }">
      <thead>
        <tr>
          <th
            v-for="column in columns"
            :key="column.key"
            scope="col"
            :style="column.width ? { width: column.width, minWidth: column.width } : undefined"
            :class="[`is-${alignOf(column)}`, { 'is-fixed': column.fixed }]"
          >
            {{ column.label }}
          </th>
          <!-- 箭头列只存在于数据行/骨架行行尾（clickable 时），表头保持 8 个数据列：
               列宽核对口径 = 8 列 940px + 行尾 24px 箭头列 = 964px（DESIGN §4.2）。
               表头补空 th 会改变既有表格契约与 TaskTable 的列数断言，故以注释固定口径。 -->
        </tr>
      </thead>
      <tbody v-if="loading">
        <tr v-for="n in skeletonRows" :key="`sk-${n}`" class="table__skeleton-row">
          <td v-for="column in columns" :key="column.key">
            <span class="sk-block" />
          </td>
          <td v-if="clickable" class="table__chevron" aria-hidden="true" />
        </tr>
      </tbody>
      <tbody v-else-if="rows.length">
        <tr
          v-for="(row, index) in rows"
          :key="keyOf(row, index)"
          :tabindex="clickable ? 0 : undefined"
          @click="clickable && emit('row-click', row, index)"
          @keydown.enter="clickable && emit('row-click', row, index)"
        >
          <td
            v-for="column in columns"
            :key="column.key"
            :class="[`is-${alignOf(column)}`, { 'is-numeric': column.numeric }]"
          >
            <slot :name="`cell-${column.key}`" :row="row" :index="index" :value="row[column.key]">
              {{ row[column.key] ?? '—' }}
            </slot>
          </td>
          <td v-if="clickable" class="table__chevron" aria-hidden="true">›</td>
        </tr>
      </tbody>
      <tbody v-else>
        <tr>
          <td :colspan="colCount + (clickable ? 1 : 0)" class="table__empty">
            <slot name="empty">
              <EmptyState :title="emptyTitle" :description="emptyHint" size="sm" />
            </slot>
          </td>
        </tr>
      </tbody>
    </table>
  </div>
</template>

<style scoped>
.table-wrap {
  width: 100%;
  overflow: auto;
  border: 1px solid var(--c-line);
  border-radius: var(--r-lg);
  background: var(--c-surface);
}
.table {
  width: 100%;
  border-collapse: separate;
  border-spacing: 0;
  table-layout: auto;
}
.table thead th {
  position: sticky;
  top: 0;
  z-index: var(--z-thead);
  height: 40px;
  padding: 0 var(--sp-3);
  background: var(--c-surface-hover);
  border-bottom: 1px solid var(--c-line);
  font-size: var(--fs-caption);
  font-weight: 600;
  color: var(--c-ink-500);
  text-align: left;
  white-space: nowrap;
  letter-spacing: 0.02em;
}
.table tbody td {
  height: 48px;
  padding: 0 var(--sp-3);
  border-bottom: 1px solid var(--c-line-soft);
  font-size: var(--fs-body-sm);
  color: var(--c-ink-900);
  vertical-align: middle;
}
.table--dense tbody td {
  height: 40px;
}
.table tbody tr:last-child td {
  border-bottom: 0;
}
.table tbody tr {
  transition: background-color var(--t-hover) var(--ease);
}
.table--clickable tbody tr {
  cursor: pointer;
}
.table--clickable tbody tr:hover,
.table--clickable tbody tr:focus-visible {
  background: var(--c-surface-hover);
  outline: none;
}
.table--clickable tbody tr:hover .table__chevron {
  color: var(--c-ink-500);
}
.is-left {
  text-align: left;
}
.is-right {
  text-align: right;
}
.is-center {
  text-align: center;
}
.is-numeric {
  font-variant-numeric: tabular-nums;
  white-space: nowrap;
}
.table__chevron {
  color: var(--c-ink-400);
  font-size: var(--fs-h3);
  transition: color var(--t-hover) var(--ease);
}
/* 行尾箭头列：声明宽 24px，外加 8px 右侧留白，表头保持 8 个数据列 */
.table tbody td.table__chevron {
  width: 24px;
  padding: 0 var(--sp-2) 0 0;
  text-align: right;
}
.table__empty {
  height: 220px;
  text-align: center;
}
.table__skeleton-row td {
  height: 48px;
}
.table__skeleton-row .sk-block {
  display: block;
  height: 12px;
  width: 60%;
  border-radius: var(--r-sm);
}
</style>
