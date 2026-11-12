<script setup lang="ts">
/**
 * 主题配置表（DESIGN §3.2）：与「项目配置」同交互——行内排序、即时启停、按引用保护删除。
 *
 * - 主数据由页面加载后传入（`items`），本组件只做写操作并 emit `refresh`；
 * - 颜色列同时给圆点与色名（不靠色觉），色名来自后端 palette，组件不写死 hex；
 * - 删除只对 `usageCount === 0` 开放，被引用的行显示「—」并说明只能停用；
 * - 排序 Enter/失焦提交、Esc 还原、0–99 校验，失败 toast 并回滚草稿。
 */
import { computed, ref, watch } from 'vue'
import AppBadge from '@/components/ui/AppBadge.vue'
import AppButton from '@/components/ui/AppButton.vue'
import ConfirmDialog from '@/components/ui/ConfirmDialog.vue'
import { themesApi } from '@/api/themes'
import { toApiError, type ApiError } from '@/api/http'
import { useToast } from '@/composables/useToast'
import type { SourcePaletteEntry, ThemeRow } from '@/types/api'
import ThemeFormModal from './ThemeFormModal.vue'

const MAX_THEMES = 100

const props = withDefaults(
  defineProps<{
    items: ThemeRow[]
    loading?: boolean
    error?: ApiError | null
  }>(),
  { loading: false, error: null },
)

const emit = defineEmits<{ refresh: [] }>()

const toast = useToast()

const sortDraft = ref<Record<number, string>>({})
const sortSaving = ref<number | null>(null)
const busyId = ref<number | null>(null)
const highlightId = ref<number | null>(null)
const palette = ref<SourcePaletteEntry[]>([])

const modalOpen = ref(false)
const editing = ref<ThemeRow | null>(null)
const deleting = ref<ThemeRow | null>(null)
const deleteLoading = ref(false)

const limitReached = computed(() => props.items.length >= MAX_THEMES)
const nextSort = computed(() => {
  const max = props.items.reduce((acc, item) => Math.max(acc, item.sort ?? 0), -1)
  return Math.min(99, max + 1)
})

watch(
  () => props.items,
  (items) => {
    const next: Record<number, string> = {}
    for (const item of items) next[item.id] = String(item.sort ?? 0)
    sortDraft.value = next
  },
  { immediate: true, deep: true },
)

void loadPalette()

async function loadPalette(): Promise<void> {
  try {
    palette.value = await themesApi.palette()
  } catch {
    // 色名只是辅助信息，取不到时颜色列退化为纯圆点，不阻断主数据编辑
    palette.value = []
  }
}

function colorName(color: string): string {
  return palette.value.find((entry) => entry.color.toLowerCase() === (color ?? '').toLowerCase())?.name ?? ''
}

function flash(id: number): void {
  highlightId.value = id
  window.setTimeout(() => {
    if (highlightId.value === id) highlightId.value = null
  }, 1200)
}

function openCreate(): void {
  if (limitReached.value) return
  editing.value = null
  modalOpen.value = true
}

function openEdit(item: ThemeRow): void {
  editing.value = item
  modalOpen.value = true
}

function onSaved(item: ThemeRow): void {
  toast.success(editing.value ? `已保存主题「${item.name}」` : `已创建主题「${item.name}」`)
  flash(item.id)
  emit('refresh')
}

async function toggle(item: ThemeRow): Promise<void> {
  if (busyId.value !== null) return
  busyId.value = item.id
  try {
    await themesApi.setEnabled(item.id, !item.enabled)
    toast.success(item.enabled ? `已停用主题「${item.name}」` : `已启用主题「${item.name}」`)
    emit('refresh')
  } catch (error) {
    toast.error(toApiError(error).message)
  } finally {
    busyId.value = null
  }
}

function draftOf(item: ThemeRow): string {
  return sortDraft.value[item.id] ?? String(item.sort ?? 0)
}

function onSortInput(item: ThemeRow, event: Event): void {
  sortDraft.value = { ...sortDraft.value, [item.id]: (event.target as HTMLInputElement).value }
}

async function commitSort(item: ThemeRow): Promise<void> {
  if (sortSaving.value !== null) return
  const raw = draftOf(item).trim()
  if (!/^\d{1,2}$/.test(raw) || Number(raw) > 99) {
    toast.error('排序需在 0–99 之间')
    sortDraft.value = { ...sortDraft.value, [item.id]: String(item.sort ?? 0) }
    return
  }
  const sort = Number(raw)
  if (sort === (item.sort ?? 0)) return
  sortSaving.value = item.id
  try {
    await themesApi.update(item.id, {
      name: item.name,
      color: item.color,
      sort,
      enabled: item.enabled,
    })
    toast.success(`已调整「${item.name}」的排序`)
    emit('refresh')
  } catch (error) {
    sortDraft.value = { ...sortDraft.value, [item.id]: String(item.sort ?? 0) }
    toast.error(toApiError(error).message)
  } finally {
    sortSaving.value = null
  }
}

function resetSort(item: ThemeRow): void {
  sortDraft.value = { ...sortDraft.value, [item.id]: String(item.sort ?? 0) }
}

function blurInput(event: Event): void {
  ;(event.target as HTMLInputElement).blur()
}

function askDelete(item: ThemeRow): void {
  if (item.usageCount > 0) return
  deleting.value = item
}

async function confirmDelete(): Promise<void> {
  const item = deleting.value
  if (!item || deleteLoading.value) return
  deleteLoading.value = true
  try {
    await themesApi.remove(item.id)
    toast.success(`已删除主题「${item.name}」`)
    deleting.value = null
    emit('refresh')
  } catch (error) {
    toast.error(toApiError(error).message)
  } finally {
    deleteLoading.value = false
  }
}
</script>

<template>
  <div class="theme-config">
    <div class="theme-config__bar">
      <p class="theme-config__usage">
        共 <strong>{{ items.length }}</strong> / {{ MAX_THEMES }} 个主题
        <span v-if="limitReached" class="theme-config__limit">已达 100 个上限，删除不再使用的主题后再新建。</span>
        <span v-else class="theme-config__hint">停用的主题不可被新任务选择，历史任务仍会显示并标注「已停用」。</span>
      </p>
      <AppButton variant="primary" size="compact" :disabled="limitReached || loading" @click="openCreate">
        + 新建主题
      </AppButton>
    </div>

    <div v-if="error" class="theme-config__error" role="alert">
      <span>{{ error.message || '主题配置加载失败，请重试。' }}</span>
      <button type="button" class="link-action" @click="emit('refresh')">重试</button>
    </div>

    <div v-if="loading" class="theme-config__skeleton" aria-hidden="true">
      <span v-for="n in 4" :key="n" class="theme-config__skeleton-row" />
    </div>

    <p v-else-if="!items.length && !error" class="theme-config__empty">
      还没有主题。先建 1–2 个主题（如「安全」「交付」），任务就能按主题汇总到甘特视图。
    </p>

    <div v-else-if="items.length" class="theme-config__scroll">
      <table class="theme-table">
        <colgroup>
          <col style="width: 200px" />
          <col style="width: 132px" />
          <col style="width: 96px" />
          <col style="width: 96px" />
          <col style="width: 80px" />
          <col style="width: 152px" />
        </colgroup>
        <thead>
          <tr>
            <th scope="col">名称</th>
            <th scope="col">颜色</th>
            <th scope="col">状态</th>
            <th scope="col">排序</th>
            <th scope="col">引用</th>
            <th scope="col">操作</th>
          </tr>
        </thead>
        <tbody>
          <tr
            v-for="item in items"
            :key="item.id"
            :data-id="item.id"
            :class="{ 'is-highlight': highlightId === item.id, 'is-busy': busyId === item.id }"
          >
            <td class="cell-name">
              <span class="cell-name__dot" :style="{ backgroundColor: item.color }" aria-hidden="true" />
              <span class="cell-name__text" :title="item.name">{{ item.name }}</span>
              <span v-if="!item.enabled" class="cell-name__off">（已停用）</span>
            </td>
            <td class="cell-color">
              <span class="cell-color__dot" :style="{ backgroundColor: item.color }" aria-hidden="true" />
              <span>{{ colorName(item.color) || '自定义' }}</span>
            </td>
            <td>
              <AppBadge :tone="item.enabled ? 'success' : 'neutral'" dot>
                {{ item.enabled ? '启用' : '停用' }}
              </AppBadge>
            </td>
            <td>
              <input
                class="sort-input"
                type="number"
                min="0"
                max="99"
                :value="draftOf(item)"
                :aria-label="`排序：${item.name}`"
                :disabled="sortSaving === item.id"
                @input="onSortInput(item, $event)"
                @blur="commitSort(item)"
                @keydown.enter.prevent="blurInput($event)"
                @keydown.esc.prevent="resetSort(item)"
              />
            </td>
            <td class="cell-usage">{{ item.usageCount }}</td>
            <td>
              <div class="row-actions">
                <button type="button" class="link-action" @click="openEdit(item)">编辑</button>
                <button
                  type="button"
                  class="link-action"
                  :disabled="busyId === item.id"
                  @click="toggle(item)"
                >
                  {{ item.enabled ? '停用' : '启用' }}
                </button>
                <button
                  v-if="item.usageCount === 0"
                  type="button"
                  class="link-action link-action--danger"
                  @click="askDelete(item)"
                >
                  删除
                </button>
                <span v-else class="row-actions__blocked" :title="`该主题已被 ${item.usageCount} 条任务使用，只能停用`">
                  —
                </span>
              </div>
            </td>
          </tr>
        </tbody>
      </table>
    </div>

    <p class="theme-config__note">主题用于给任务归类并汇总到甘特视图；颜色只决定标签与甘特条配色。</p>

    <ThemeFormModal
      :open="modalOpen"
      :row="editing"
      :next-sort="nextSort"
      @saved="onSaved"
      @close="modalOpen = false"
    />

    <ConfirmDialog
      :open="!!deleting"
      title="删除主题"
      :description="deleting ? `确定删除主题「${deleting.name}」吗？删除后不可恢复，且只允许删除未被任务引用的主题。` : ''"
      confirm-label="删除"
      tone="danger"
      :loading="deleteLoading"
      @confirm="confirmDelete"
      @close="deleting = null"
    />
  </div>
</template>

<style scoped>
.theme-config {
  display: flex;
  flex-direction: column;
  gap: var(--sp-3);
  min-width: 0;
}
.theme-config__bar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--sp-3);
  flex-wrap: wrap;
}
.theme-config__usage {
  margin: 0;
  font-size: var(--fs-body-sm);
  color: var(--c-ink-700);
}
.theme-config__usage strong {
  font-weight: 600;
  color: var(--c-ink-900);
}
.theme-config__hint,
.theme-config__limit {
  margin-left: var(--sp-2);
  font-size: var(--fs-caption);
  color: var(--c-ink-500);
}
.theme-config__limit {
  color: var(--c-warning-text);
}
.theme-config__error {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--sp-3);
  padding: var(--sp-2) var(--sp-3);
  border: 1px solid var(--c-danger-border);
  border-radius: var(--r-sm);
  background: var(--c-danger-soft);
  color: var(--c-danger-text);
  font-size: var(--fs-caption);
}
.theme-config__skeleton {
  display: flex;
  flex-direction: column;
  gap: var(--sp-2);
}
.theme-config__skeleton-row {
  height: 40px;
  border-radius: var(--r-sm);
  background: var(--c-line-soft);
  animation: theme-skeleton 1.2s ease-in-out infinite;
}
@keyframes theme-skeleton {
  0%,
  100% {
    opacity: 0.6;
  }
  50% {
    opacity: 1;
  }
}
.theme-config__empty {
  margin: 0;
  padding: var(--sp-5);
  border: 1px dashed var(--c-line);
  border-radius: var(--r-md);
  color: var(--c-ink-500);
  font-size: var(--fs-body-sm);
  text-align: center;
}
.theme-config__scroll {
  overflow-x: auto;
}
.theme-table {
  width: 100%;
  border-collapse: collapse;
  font-size: var(--fs-body-sm);
}
.theme-table th {
  height: 40px;
  padding: 0 var(--sp-2);
  border-bottom: 1px solid var(--c-line);
  color: var(--c-ink-500);
  font-size: var(--fs-caption);
  font-weight: 500;
  text-align: left;
  white-space: nowrap;
}
.theme-table td {
  height: 56px;
  padding: 0 var(--sp-2);
  border-bottom: 1px solid var(--c-line-soft);
  color: var(--c-ink-700);
  vertical-align: middle;
}
.theme-table tbody tr.is-highlight {
  animation: theme-flash 1.2s ease-out;
}
.theme-table tbody tr.is-busy {
  opacity: 0.6;
}
@keyframes theme-flash {
  from {
    background: var(--c-primary-soft);
  }
  to {
    background: transparent;
  }
}
.cell-name {
  display: flex;
  align-items: center;
  gap: 6px;
  min-width: 0;
}
.cell-name__dot {
  flex: none;
  width: 8px;
  height: 8px;
  border-radius: 50%;
}
.cell-name__text {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  color: var(--c-ink-900);
  font-weight: 500;
}
.cell-name__off {
  flex: none;
  color: var(--c-ink-400);
  font-size: var(--fs-micro);
}
.cell-color {
  display: flex;
  align-items: center;
  gap: 6px;
  color: var(--c-ink-700);
}
.cell-color__dot {
  width: 10px;
  height: 10px;
  border-radius: 50%;
  box-shadow: inset 0 0 0 1px rgb(0 0 0 / 8%);
}
.cell-usage {
  font-variant-numeric: tabular-nums;
}
.sort-input {
  box-sizing: border-box;
  width: 56px;
  height: 28px;
  padding: 0 6px;
  border: 1px solid var(--c-line);
  border-radius: var(--r-sm);
  background: var(--c-surface);
  color: var(--c-ink-900);
  font-family: inherit;
  font-size: var(--fs-caption);
  text-align: center;
}
.sort-input:hover {
  border-color: var(--c-ink-400);
}
.sort-input:focus {
  outline: none;
  border-color: var(--c-primary);
  box-shadow: var(--focus-ring);
}
.row-actions {
  display: flex;
  align-items: center;
  gap: var(--sp-2);
}
.link-action {
  padding: 0;
  border: 0;
  background: transparent;
  color: var(--c-primary);
  font-family: inherit;
  font-size: var(--fs-caption);
  cursor: pointer;
}
.link-action:hover {
  text-decoration: underline;
}
.link-action:disabled {
  color: var(--c-ink-400);
  cursor: not-allowed;
  text-decoration: none;
}
.link-action--danger {
  color: var(--c-danger-text);
}
.row-actions__blocked {
  color: var(--c-ink-400);
  cursor: default;
}
.theme-config__note {
  margin: 0;
  font-size: var(--fs-micro);
  color: var(--c-ink-400);
}
</style>
