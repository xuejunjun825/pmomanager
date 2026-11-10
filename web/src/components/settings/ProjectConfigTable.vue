<script setup lang="ts">
/**
 * 设置页 · 项目配置表（DESIGN §3.1 / TECHNICAL §4.4）。
 * - 行内操作即时生效，不走页面级「保存」；成功后 emit refresh 由页面重新 GET（不本地改数组，
 *   避免与任务池/看板读到的 meta 不一致）；
 * - 删除只在 usageCount = 0 时渲染，被引用行用「—」+ 说明解释原因，不靠 409 红框兜底；
 * - 项目达 200 个时新增按钮禁用并给出可见原因；
 * - 几何按 DESIGN：列宽 240/96/88/64/88/152 = 728 ≤ 内容宽 968，表头 40、行 56。
 */
import { computed, nextTick, onBeforeUnmount, reactive, ref, watch } from 'vue'
import AppBadge from '@/components/ui/AppBadge.vue'
import AppButton from '@/components/ui/AppButton.vue'
import ConfirmDialog from '@/components/ui/ConfirmDialog.vue'
import ProjectFormModal from './ProjectFormModal.vue'
import { projectsApi } from '@/api/projects'
import { toApiError } from '@/api/http'
import { useToast } from '@/composables/useToast'
import type { ProjectRow } from '@/types/api'

const MAX_PROJECTS = 200

const props = withDefaults(
  defineProps<{
    items: ProjectRow[]
    loading?: boolean
    error?: string | null
  }>(),
  { loading: false, error: null },
)

const emit = defineEmits<{ refresh: [] }>()

const toast = useToast()
const rootRef = ref<HTMLElement | null>(null)

const modalOpen = ref(false)
const editing = ref<ProjectRow | null>(null)
const creating = ref(false)

const deleteTarget = ref<ProjectRow | null>(null)
const deleting = ref(false)
const busyId = ref<number | null>(null)

const highlight = ref(0)
let highlightTimer: number | undefined

/** 行内排序的草稿值：Enter / 失焦提交，Esc 还原 */
const sortDraft = reactive<Record<number, string>>({})
/** 正在提交排序的行，避免连续 Enter 重复请求 */
const sortSaving = ref<number | null>(null)

const limitReached = computed(() => props.items.length >= MAX_PROJECTS)
const nextSort = computed(() =>
  Math.min(99, props.items.reduce((max, row) => Math.max(max, row.sort), -1) + 1),
)
const deleteDescription = computed(() => {
  const name = deleteTarget.value?.name ?? ''
  return `删除项目「${name}」？该项目当前未被任何任务使用，删除后不可恢复。`
})

function syncDrafts(): void {
  for (const row of props.items) sortDraft[row.id] = String(row.sort)
}

watch(
  () => props.items,
  () => {
    syncDrafts()
  },
  { immediate: true, deep: true },
)

function flash(id: number): void {
  highlight.value = id
  window.clearTimeout(highlightTimer)
  highlightTimer = window.setTimeout(() => {
    highlight.value = 0
  }, 1200)
}

function openCreate(): void {
  if (limitReached.value) return
  creating.value = true
  editing.value = null
  modalOpen.value = true
}

function openEdit(row: ProjectRow): void {
  creating.value = false
  editing.value = row
  modalOpen.value = true
}

async function onSaved(row: ProjectRow): Promise<void> {
  const wasCreating = creating.value
  modalOpen.value = false
  toast.success(wasCreating ? `已新增项目「${row.name}」` : `已保存项目「${row.name}」`)
  flash(row.id)
  editing.value = null
  creating.value = false
  emit('refresh')
  await nextTick()
  const target = rootRef.value?.querySelector<HTMLElement>(`[data-id="${row.id}"]`)
  if (target && typeof target.scrollIntoView === 'function') {
    target.scrollIntoView({ block: 'nearest' })
  }
}

async function toggle(row: ProjectRow): Promise<void> {
  if (busyId.value !== null) return
  busyId.value = row.id
  try {
    await projectsApi.setEnabled(row.id, !row.enabled)
    toast.success(
      row.enabled
        ? `已停用「${row.name}」，新建任务不再可选该项目`
        : `已启用「${row.name}」，新建任务可以选到`,
    )
    flash(row.id)
  } catch (error) {
    toast.error(toApiError(error).message || '操作失败，请重试')
  } finally {
    busyId.value = null
    emit('refresh')
  }
}

/** 排序提交：先本地校验 0–99 整数，越界不请求 + Toast 并回滚（DESIGN §3.1） */
async function commitSort(row: ProjectRow): Promise<void> {
  if (sortSaving.value === row.id) return
  const raw = String(sortDraft[row.id] ?? '').trim()
  if (raw === String(row.sort)) return
  const value = Number(raw)
  if (!/^\d{1,2}$/.test(raw) || !Number.isInteger(value) || value < 0 || value > 99) {
    toast.error('排序需在 0–99 之间')
    sortDraft[row.id] = String(row.sort)
    return
  }
  sortSaving.value = row.id
  try {
    await projectsApi.update(row.id, { name: row.name, sort: value, enabled: row.enabled })
    flash(row.id)
  } catch (error) {
    toast.error(toApiError(error).message || '排序保存失败，请重试')
    sortDraft[row.id] = String(row.sort)
  } finally {
    sortSaving.value = null
    emit('refresh')
  }
}

function onSortEnter(row: ProjectRow): void {
  void commitSort(row)
}

function onSortEsc(row: ProjectRow): void {
  sortDraft[row.id] = String(row.sort)
}

function askDelete(row: ProjectRow): void {
  deleteTarget.value = row
}

async function confirmDelete(): Promise<void> {
  const target = deleteTarget.value
  if (!target) return
  deleting.value = true
  try {
    await projectsApi.remove(target.id)
    toast.success(`已删除项目「${target.name}」`)
  } catch (error) {
    toast.error(toApiError(error).message || '删除失败，请重试')
  } finally {
    deleting.value = false
    deleteTarget.value = null
    emit('refresh')
  }
}

onBeforeUnmount(() => window.clearTimeout(highlightTimer))
</script>

<template>
  <div ref="rootRef" class="project-config">
    <div class="project-config__bar">
      <p class="project-config__usage" :class="{ 'project-config__usage--max': limitReached }">
        共 <span class="num">{{ items.length }}</span> / {{ MAX_PROJECTS }} 个项目
      </p>
      <div class="project-config__create">
        <span v-if="limitReached" class="project-config__limit">项目最多 200 个，已达上限</span>
        <AppButton
          variant="primary"
          size="compact"
          :disabled="limitReached || loading"
          :aria-disabled="limitReached || loading"
          :title="limitReached ? '项目最多 200 个，已达上限' : undefined"
          @click="openCreate"
        >
          + 新建项目
        </AppButton>
      </div>
    </div>

    <div v-if="error" class="project-config__error" role="alert">
      <p class="project-config__error-text">{{ error }}</p>
      <AppButton variant="ghost" size="compact" @click="emit('refresh')">重试</AppButton>
    </div>

    <div v-else-if="loading" class="project-config__skeleton" aria-busy="true" aria-live="polite">
      <span class="visually-hidden">正在加载项目配置</span>
      <div v-for="n in 5" :key="`sk-${n}`" class="skeleton-row">
        <span class="skeleton-bar" />
      </div>
    </div>

    <div v-else-if="!items.length" class="project-config__empty">
      <p class="project-config__empty-text">还没有项目。新建后即可在「需绑定项目」的来源里选到。</p>
      <AppButton variant="ghost" size="compact" @click="openCreate">新建项目</AppButton>
    </div>

    <div v-else class="project-config__scroll">
      <table class="proj-table">
        <caption class="visually-hidden">
          项目配置：名称、编码、状态、排序、引用任务数与操作
        </caption>
        <colgroup>
          <col style="width: 240px" />
          <col style="width: 96px" />
          <col style="width: 88px" />
          <col style="width: 64px" />
          <col style="width: 88px" />
          <col style="width: 152px" />
        </colgroup>
        <thead>
          <tr>
            <th scope="col">名称</th>
            <th scope="col">编码</th>
            <th scope="col">状态</th>
            <th scope="col" class="is-right">排序</th>
            <th scope="col" class="is-right">引用</th>
            <th scope="col">操作</th>
          </tr>
        </thead>
        <tbody>
          <tr
            v-for="row in items"
            :key="row.id"
            :data-id="row.id"
            :class="{ 'is-highlight': highlight === row.id, 'is-busy': busyId === row.id }"
          >
            <td>
              <span class="proj-name" :title="row.name">{{ row.name }}</span>
            </td>
            <td><span class="proj-code">{{ row.code }}</span></td>
            <td>
              <AppBadge :tone="row.enabled ? 'success' : 'neutral'" dot>
                {{ row.enabled ? '启用' : '已停用' }}
              </AppBadge>
            </td>
            <td class="is-right">
              <input
                v-model="sortDraft[row.id]"
                class="sort-input"
                type="number"
                inputmode="numeric"
                min="0"
                max="99"
                :aria-label="`排序：${row.name}`"
                :disabled="sortSaving === row.id"
                @blur="commitSort(row)"
                @keydown.enter.prevent="onSortEnter(row)"
                @keydown.esc.prevent="onSortEsc(row)"
              />
            </td>
            <td class="is-right num" :class="{ 'is-zero': row.usageCount === 0 }">
              {{ row.usageCount }} 条
            </td>
            <td>
              <div class="row-actions">
                <button type="button" class="link-action" @click="openEdit(row)">编辑</button>
                <button
                  type="button"
                  class="link-action"
                  :disabled="busyId !== null"
                  @click="toggle(row)"
                >
                  {{ busyId === row.id ? '处理中…' : row.enabled ? '停用' : '启用' }}
                </button>
                <button
                  v-if="row.usageCount === 0"
                  type="button"
                  class="link-action link-action--danger"
                  :disabled="busyId !== null"
                  @click="askDelete(row)"
                >
                  删除
                </button>
                <span
                  v-else
                  class="row-actions__blocked"
                  :title="`已被 ${row.usageCount} 条任务使用，只能停用`"
                  :aria-label="`已被 ${row.usageCount} 条任务使用，只能停用`"
                >
                  —
                </span>
              </div>
            </td>
          </tr>
        </tbody>
      </table>
    </div>

    <p class="project-config__note">
      已被任务使用的项目只能停用，不能删除；项目最多 200 个<span v-if="limitReached">，已达上限</span>。
    </p>

    <ProjectFormModal
      :open="modalOpen"
      :row="editing"
      :next-sort="nextSort"
      @saved="onSaved"
      @close="modalOpen = false"
    />

    <ConfirmDialog
      :open="!!deleteTarget"
      title="删除项目"
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
.project-config {
  display: flex;
  flex-direction: column;
  gap: var(--sp-3);
}
.project-config__bar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--sp-3);
  min-height: 28px;
}
.project-config__usage {
  margin: 0;
  font-size: var(--fs-caption);
  line-height: var(--lh-caption);
  font-variant-numeric: tabular-nums;
  color: var(--c-ink-500);
}
.project-config__usage--max {
  color: var(--c-warn);
}
.project-config__create {
  display: flex;
  align-items: center;
  gap: var(--sp-3);
}
.project-config__limit {
  font-size: var(--fs-caption);
  line-height: var(--lh-caption);
  color: var(--c-warn);
}
.project-config__error {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--sp-3);
  padding: 10px var(--sp-3);
  border: 1px solid var(--c-danger-border);
  border-radius: var(--r-md);
  background: var(--c-danger-soft);
}
.project-config__error-text {
  margin: 0;
  font-size: var(--fs-body-sm);
  line-height: var(--lh-body-sm);
  color: var(--c-danger-text);
}
.project-config__empty {
  display: flex;
  align-items: center;
  gap: var(--sp-3);
  padding: var(--sp-4) var(--sp-3);
  border: 1px dashed var(--c-line);
  border-radius: var(--r-md);
}
.project-config__empty-text {
  margin: 0;
  font-size: var(--fs-body-sm);
  color: var(--c-ink-600);
}
.project-config__skeleton {
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

.project-config__scroll {
  overflow-x: auto;
}
.proj-table {
  width: 100%;
  min-width: 728px;
  border-collapse: collapse;
  table-layout: fixed;
}
.proj-table th {
  height: 40px;
  padding: 0 var(--sp-2) 0 0;
  border-bottom: 1px solid var(--c-line);
  font-size: var(--fs-caption);
  font-weight: 500;
  line-height: var(--lh-caption);
  color: var(--c-ink-500);
  text-align: left;
  white-space: nowrap;
}
.proj-table th:first-child {
  padding-left: var(--sp-2);
}
.proj-table td {
  height: 56px;
  padding: 6px var(--sp-2) 6px 0;
  border-bottom: 1px solid var(--c-line-soft);
  font-size: var(--fs-body-sm);
  line-height: var(--lh-body-sm);
  color: var(--c-ink-700);
  vertical-align: middle;
}
.proj-table td:first-child {
  padding-left: var(--sp-2);
}
.proj-table tbody tr {
  transition: background var(--t-hover) var(--ease);
}
.proj-table tbody tr:hover {
  background: var(--c-surface-hover);
}
.proj-table tbody tr.is-busy td {
  color: var(--c-ink-400);
}
.proj-table .is-right {
  text-align: right;
}
.proj-table .num {
  font-variant-numeric: tabular-nums;
}
.proj-table .is-zero {
  color: var(--c-ink-400);
}

/** 名称最多两行（20 字在 240px 内可读），title 兜底全名 */
.proj-name {
  display: -webkit-box;
  overflow: hidden;
  font-size: var(--fs-body);
  font-weight: 600;
  line-height: var(--lh-body);
  color: var(--c-ink-900);
  word-break: break-all;
  -webkit-box-orient: vertical;
  -webkit-line-clamp: 2;
  line-clamp: 2;
}
.proj-code {
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

.sort-input {
  width: 56px;
  height: 28px;
  padding: 0 6px;
  border: 1px solid var(--c-line);
  border-radius: var(--r-sm);
  background: var(--c-surface);
  color: var(--c-ink-700);
  font-family: var(--font-num);
  font-size: var(--fs-caption);
  font-variant-numeric: tabular-nums;
  text-align: right;
  transition:
    border-color var(--t-hover) var(--ease),
    box-shadow var(--t-hover) var(--ease);
}
.sort-input:hover:not(:disabled) {
  border-color: var(--c-ink-400);
}
.sort-input:focus-visible {
  border-color: var(--c-primary);
  outline: 2px solid var(--c-primary);
  outline-offset: 1px;
}
.sort-input:disabled {
  color: var(--c-ink-400);
  background: var(--c-line-soft);
}
.sort-input::-webkit-inner-spin-button,
.sort-input::-webkit-outer-spin-button {
  margin: 0;
  -webkit-appearance: none;
}

.row-actions {
  display: flex;
  align-items: center;
  gap: var(--sp-3);
}
.row-actions__blocked {
  color: var(--c-ink-400);
  cursor: help;
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

.proj-table tbody tr.is-highlight td {
  animation: project-row-flash 1200ms var(--ease) both;
}
@keyframes project-row-flash {
  from {
    background-color: var(--c-primary-soft);
  }
  to {
    background-color: transparent;
  }
}

.project-config__note {
  margin: 0;
  padding-top: var(--sp-3);
  border-top: 1px solid var(--c-line-soft);
  font-size: var(--fs-caption);
  line-height: var(--lh-caption);
  color: var(--c-ink-400);
}

@media (prefers-reduced-motion: reduce) {
  .proj-table tbody tr.is-highlight td {
    animation: none;
    background-color: transparent;
  }
}
</style>
