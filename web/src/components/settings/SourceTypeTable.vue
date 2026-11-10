<script setup lang="ts">
/**
 * 设置页 · 来源配置表（DESIGN §3.1 / AC-1、AC-4、AC-6）。
 * - 行内操作即时生效，不走页面级「保存」；成功后 emit refresh 由页面重新 GET；
 * - 删除只在 usageCount = 0 且非内置时渲染（不靠 409 红框兜底）；
 * - locked（内置会议决议）不渲染停用/删除，改用「内置」标签解释；
 * - 启用数达 20 时新增按钮禁用并给出可见原因。
 * 表头/行高与列宽按 DESIGN §1.7：头 40 / 行 56，七列合计 844 ≤ 设置页内容宽 1008。
 */
import { computed, nextTick, onBeforeUnmount, ref, watch } from 'vue'
import AppBadge from '@/components/ui/AppBadge.vue'
import AppButton from '@/components/ui/AppButton.vue'
import ConfirmDialog from '@/components/ui/ConfirmDialog.vue'
import SourceTypeFormModal from './SourceTypeFormModal.vue'
import { sourceTypesApi } from '@/api/sourceTypes'
import { toApiError } from '@/api/http'
import { useToast } from '@/composables/useToast'
import { SOURCE_FORM_LABELS } from '@/types/enums'
import type { SourcePaletteEntry, SourceTypeRow } from '@/types/api'

const MAX_ENABLED = 20

const props = withDefaults(
  defineProps<{
    items: SourceTypeRow[]
    palette: SourcePaletteEntry[]
    loading?: boolean
    error?: string
  }>(),
  { loading: false, error: '' },
)

const emit = defineEmits<{ refresh: [] }>()

const toast = useToast()
const rootRef = ref<HTMLElement | null>(null)

const modalOpen = ref(false)
const editing = ref<SourceTypeRow | null>(null)
const creating = ref(false)

const deleteTarget = ref<SourceTypeRow | null>(null)
const deleting = ref(false)
const busyCode = ref('')

const highlight = ref('')
/** 新增成功后等页面把新列表取回来，再定位高亮那一行 */
const pendingCode = ref('')
let highlightTimer: number | undefined

const enabledCount = computed(() => props.items.filter((row) => row.enabled).length)
const limitReached = computed(() => enabledCount.value >= MAX_ENABLED)
const nextSort = computed(() =>
  Math.min(99, props.items.reduce((max, row) => Math.max(max, row.sort), -1) + 1),
)
const deleteDescription = computed(() => {
  const name = deleteTarget.value?.name ?? ''
  return `删除来源「${name}」？该来源当前未被任何任务使用，删除后不可恢复。`
})

function colorName(hex: string): string {
  return props.palette.find((entry) => entry.color.toLowerCase() === hex.toLowerCase())?.name ?? '自定义'
}

function flash(code: string): void {
  highlight.value = code
  window.clearTimeout(highlightTimer)
  highlightTimer = window.setTimeout(() => {
    highlight.value = ''
  }, 1300)
}

function openCreate(): void {
  if (limitReached.value) return
  creating.value = true
  editing.value = null
  modalOpen.value = true
}

function openEdit(row: SourceTypeRow): void {
  creating.value = false
  editing.value = row
  modalOpen.value = true
}

function onSaved(row: SourceTypeRow): void {
  modalOpen.value = false
  toast.success(creating.value ? `已新增来源「${row.name}」` : `已保存来源「${row.name}」`)
  if (creating.value) {
    pendingCode.value = row.code
  } else {
    flash(row.code)
  }
  editing.value = null
  creating.value = false
  emit('refresh')
}

async function toggle(row: SourceTypeRow): Promise<void> {
  if (busyCode.value) return
  busyCode.value = row.code
  try {
    await sourceTypesApi.setEnabled(row.code, !row.enabled)
    toast.success(
      row.enabled ? `已停用「${row.name}」，新建任务不再显示该来源` : `已启用「${row.name}」`,
    )
    flash(row.code)
    emit('refresh')
  } catch (error) {
    toast.error(toApiError(error).message || '操作失败，请重试')
    emit('refresh')
  } finally {
    busyCode.value = ''
  }
}

function askDelete(row: SourceTypeRow): void {
  deleteTarget.value = row
}

async function confirmDelete(): Promise<void> {
  const target = deleteTarget.value
  if (!target) return
  deleting.value = true
  try {
    await sourceTypesApi.remove(target.code)
    toast.success(`已删除来源「${target.name}」`)
  } catch (error) {
    toast.error(toApiError(error).message || '删除失败，请重试')
  } finally {
    deleting.value = false
    deleteTarget.value = null
    emit('refresh')
  }
}

watch(
  () => props.items,
  async () => {
    if (!pendingCode.value) return
    if (!props.items.some((row) => row.code === pendingCode.value)) return
    const code = pendingCode.value
    pendingCode.value = ''
    flash(code)
    await nextTick()
    const target = rootRef.value?.querySelector<HTMLElement>(`[data-code="${code}"]`)
    if (target && typeof target.scrollIntoView === 'function') {
      target.scrollIntoView({ block: 'nearest' })
    }
  },
)

onBeforeUnmount(() => window.clearTimeout(highlightTimer))
</script>

<template>
  <div ref="rootRef" class="source-config">
    <div class="source-config__bar">
      <p class="source-config__usage" :class="{ 'source-config__usage--max': limitReached }">
        已启用 <span class="num">{{ enabledCount }}</span> / {{ MAX_ENABLED }}
      </p>
      <div class="source-config__create">
        <span v-if="limitReached" class="source-config__limit">来源类型最多 20 个，已达上限</span>
        <AppButton
          variant="primary"
          size="compact"
          :disabled="limitReached || loading"
          :title="limitReached ? '来源类型最多 20 个，已达上限' : undefined"
          @click="openCreate"
        >
          + 新增来源
        </AppButton>
      </div>
    </div>

    <div v-if="error" class="source-config__error" role="alert">
      <p class="source-config__error-text">{{ error }}</p>
      <AppButton variant="ghost" size="compact" @click="emit('refresh')">重试</AppButton>
    </div>

    <div v-else-if="loading" class="source-config__skeleton" aria-busy="true" aria-live="polite">
      <span class="visually-hidden">正在加载来源配置</span>
      <div v-for="n in 5" :key="`sk-${n}`" class="skeleton-row">
        <span class="skeleton-bar" />
      </div>
    </div>

    <div v-else-if="!items.length" class="source-config__empty">
      <p class="source-config__empty-text">内置来源缺失，请检查数据迁移。</p>
      <AppButton variant="ghost" size="compact" @click="emit('refresh')">重试</AppButton>
    </div>

    <div v-else class="source-config__scroll">
      <table class="src-table">
        <caption class="visually-hidden">
          来源配置：名称、形态、颜色、排序、状态与引用任务数
        </caption>
        <colgroup>
          <col style="width: 220px" />
          <col style="width: 120px" />
          <col style="width: 88px" />
          <col style="width: 64px" />
          <col style="width: 88px" />
          <col style="width: 88px" />
          <col style="width: 176px" />
        </colgroup>
        <thead>
          <tr>
            <th scope="col">来源</th>
            <th scope="col">形态</th>
            <th scope="col">颜色</th>
            <th scope="col" class="is-right">排序</th>
            <th scope="col">状态</th>
            <th scope="col" class="is-right">引用</th>
            <th scope="col">操作</th>
          </tr>
        </thead>
        <tbody>
          <tr
            v-for="row in items"
            :key="row.code"
            :data-code="row.code"
            :class="{ 'is-highlight': highlight === row.code, 'is-busy': busyCode === row.code }"
          >
            <td>
              <span class="src-name">
                <span class="src-name__text" :title="row.name">{{ row.name }}</span>
                <AppBadge v-if="row.builtin" tone="neutral" size="sm">内置</AppBadge>
              </span>
              <span class="src-code">{{ row.code }}</span>
            </td>
            <td class="src-form">{{ SOURCE_FORM_LABELS[row.form] }}</td>
            <td>
              <span class="src-color">
                <span class="src-color__dot" :style="{ backgroundColor: row.color }" aria-hidden="true" />
                <span class="src-color__name">{{ colorName(row.color) }}</span>
              </span>
            </td>
            <td class="is-right num">{{ row.sort }}</td>
            <td>
              <AppBadge :tone="row.enabled ? 'success' : 'neutral'" dot>
                {{ row.enabled ? '启用' : '已停用' }}
              </AppBadge>
            </td>
            <td class="is-right num" :class="{ 'is-zero': row.usageCount === 0 }">
              {{ row.usageCount }} 条
            </td>
            <td>
              <div class="row-actions">
                <button type="button" class="link-action" @click="openEdit(row)">编辑</button>
                <button
                  v-if="!row.locked"
                  type="button"
                  class="link-action"
                  :disabled="busyCode === row.code"
                  @click="toggle(row)"
                >
                  {{ busyCode === row.code ? '处理中…' : row.enabled ? '停用' : '启用' }}
                </button>
                <button
                  v-if="!row.locked && row.usageCount === 0"
                  type="button"
                  class="link-action link-action--danger"
                  :disabled="busyCode === row.code"
                  @click="askDelete(row)"
                >
                  删除
                </button>
              </div>
            </td>
          </tr>
        </tbody>
      </table>
    </div>

    <p class="source-config__note">
      已被任务使用的来源只能停用，不能删除；「会议决议」为系统内置，不可停用。
    </p>

    <SourceTypeFormModal
      :open="modalOpen"
      :row="editing"
      :palette="palette"
      :next-sort="nextSort"
      @saved="onSaved"
      @close="modalOpen = false"
    />

    <ConfirmDialog
      :open="!!deleteTarget"
      title="删除来源"
      :description="deleteDescription"
      confirm-label="删除"
      tone="danger"
      :loading="deleting"
      @confirm="confirmDelete"
      @close="deleteTarget = null"
    />
  </div>
</template>

<style scoped>
.source-config {
  display: flex;
  flex-direction: column;
  gap: var(--sp-3);
}
.source-config__bar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--sp-3);
  min-height: 28px;
}
.source-config__usage {
  margin: 0;
  font-size: var(--fs-caption);
  line-height: var(--lh-caption);
  font-variant-numeric: tabular-nums;
  color: var(--c-ink-500);
}
.source-config__usage--max {
  color: var(--c-warn);
}
.source-config__create {
  display: flex;
  align-items: center;
  gap: var(--sp-3);
}
.source-config__limit {
  font-size: var(--fs-caption);
  line-height: var(--lh-caption);
  color: var(--c-warn);
}

.source-config__error {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--sp-3);
  padding: 10px var(--sp-3);
  border: 1px solid var(--c-danger-border);
  border-radius: var(--r-md);
  background: var(--c-danger-soft);
}
.source-config__error-text {
  margin: 0;
  font-size: var(--fs-body-sm);
  line-height: var(--lh-body-sm);
  color: var(--c-danger-text);
}
.source-config__empty {
  display: flex;
  align-items: center;
  gap: var(--sp-3);
  padding: var(--sp-4) var(--sp-3);
  border: 1px dashed var(--c-line);
  border-radius: var(--r-md);
}
.source-config__empty-text {
  margin: 0;
  font-size: var(--fs-body-sm);
  color: var(--c-ink-600);
}

.source-config__skeleton {
  display: flex;
  flex-direction: column;
}
.skeleton-row {
  display: flex;
  align-items: center;
  height: 56px;
  border-bottom: 1px solid var(--c-line-soft);
}
.skeleton-bar {
  width: 60%;
  height: 12px;
  border-radius: var(--r-sm);
  background: var(--c-line-soft);
}

.source-config__scroll {
  overflow-x: auto;
}
.src-table {
  width: 100%;
  min-width: 844px;
  border-collapse: collapse;
  table-layout: fixed;
}
.src-table th {
  height: 40px;
  padding: 0 var(--sp-2) 0 0;
  border-bottom: 1px solid var(--c-line);
  font-size: var(--fs-caption);
  font-weight: 500;
  line-height: var(--lh-caption);
  color: var(--c-ink-500);
  text-align: left;
}
.src-table th:first-child {
  padding-left: var(--sp-2);
}
.src-table td {
  height: 56px;
  padding: 6px var(--sp-2) 6px 0;
  border-bottom: 1px solid var(--c-line-soft);
  font-size: var(--fs-body-sm);
  line-height: var(--lh-body-sm);
  color: var(--c-ink-700);
  vertical-align: middle;
}
.src-table td:first-child {
  padding-left: var(--sp-2);
}
.src-table tbody tr {
  transition: background var(--t-hover) var(--ease);
}
.src-table tbody tr:hover {
  background: var(--c-surface-hover);
}
.src-table tbody tr.is-busy td {
  color: var(--c-ink-400);
}
.src-table .is-right {
  text-align: right;
}
.src-table .num {
  font-variant-numeric: tabular-nums;
}
.src-table .is-zero {
  color: var(--c-ink-400);
}

.src-name {
  display: flex;
  align-items: center;
  gap: 6px;
  min-width: 0;
}
.src-name__text {
  overflow: hidden;
  font-size: var(--fs-body);
  font-weight: 600;
  line-height: var(--lh-body);
  color: var(--c-ink-900);
  text-overflow: ellipsis;
  white-space: nowrap;
}
.src-code {
  display: block;
  overflow: hidden;
  font-family: var(--font-num);
  font-size: var(--fs-micro);
  line-height: var(--lh-micro);
  letter-spacing: 0.02em;
  text-transform: uppercase;
  color: var(--c-ink-500);
  text-overflow: ellipsis;
  white-space: nowrap;
}
.src-form {
  color: var(--c-ink-600);
}
.src-color {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  min-width: 0;
}
.src-color__dot {
  flex: none;
  width: 12px;
  height: 12px;
  border-radius: var(--r-pill);
  box-shadow: inset 0 0 0 1px rgba(15, 23, 42, 0.12);
}
.src-color__name {
  overflow: hidden;
  font-size: var(--fs-caption);
  color: var(--c-ink-600);
  text-overflow: ellipsis;
  white-space: nowrap;
}

.row-actions {
  display: flex;
  align-items: center;
  gap: var(--sp-3);
}
.link-action {
  height: 28px;
  padding: 0;
  border: 0;
  border-radius: var(--r-sm);
  background: transparent;
  color: var(--c-primary);
  font-family: inherit;
  font-size: var(--fs-body-sm);
  line-height: var(--lh-body-sm);
  cursor: pointer;
  transition: color var(--t-hover) var(--ease);
}
.link-action:hover:not(:disabled) {
  color: var(--c-primary-hover);
  text-decoration: underline;
  text-underline-offset: 2px;
}
.link-action--danger {
  color: var(--c-danger-text);
}
.link-action--danger:hover:not(:disabled) {
  color: var(--c-danger);
}
.link-action:disabled {
  color: var(--c-ink-400);
  cursor: not-allowed;
}
.link-action:focus-visible {
  outline: 2px solid var(--c-primary);
  outline-offset: 2px;
}

.src-table tbody tr.is-highlight td {
  animation: source-row-flash 1200ms var(--ease) both;
}
@keyframes source-row-flash {
  from {
    background-color: var(--c-primary-soft);
  }
  to {
    background-color: transparent;
  }
}

.source-config__note {
  margin: 0;
  padding-top: var(--sp-3);
  border-top: 1px solid var(--c-line-soft);
  font-size: var(--fs-caption);
  line-height: var(--lh-caption);
  color: var(--c-ink-400);
}
.source-config__note--warn {
  padding-top: 0;
  border-top: 0;
  color: var(--c-warn);
}

@media (prefers-reduced-motion: reduce) {
  .src-table tbody tr.is-highlight td {
    animation: none;
    background-color: transparent;
  }
}
</style>
