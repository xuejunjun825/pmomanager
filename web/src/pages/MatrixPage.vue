<script setup lang="ts">
/**
 * 人维度闭环矩阵（AC-15 / §4.8）。
 * 五个数字列互斥：逾期任务只出现在「逾期」列，不再计入原状态列——所以每格下钻都带
 * overdue=false（逾期列除外），点击后的任务池数字必须与格内一致。
 * 合计行是五列之和（不含已取消），无法用一个任务池筛选表达，故为静态数字、不可下钻。
 */
import { computed, onMounted, ref, watch } from 'vue'
import PageHeader from '@/components/ui/PageHeader.vue'
import AppSelect from '@/components/ui/AppSelect.vue'
import EmptyState from '@/components/ui/EmptyState.vue'
import ErrorState from '@/components/ui/ErrorState.vue'
import SkeletonTable from '@/components/ui/SkeletonTable.vue'
import MatrixCell from '@/components/ui/MatrixCell.vue'
import { useQuerySync, type QueryKinds } from '@/composables/useQuerySync'
import { matrixApi, matrixDrilldown, MATRIX_BUCKETS, type MatrixBucketKey } from '@/api/matrix'
import { metaApi } from '@/api/meta'
import { toApiError, type ApiError } from '@/api/http'
import { useToast } from '@/composables/useToast'
import { formatNumber, formatPercent } from '@/utils/format'
import type { MatrixRange, SourceType } from '@/types/enums'
import type { MatrixRow, MatrixView, SourceTypeOption } from '@/types/api'
import { sourceOptions as buildSourceOptions, type SourceOption } from '@/utils/sourceOptions'

interface MatrixQueryState {
  source: SourceType | ''
  range: MatrixRange
  activeOnly: boolean
}

const DEFAULTS: MatrixQueryState = { source: '', range: 'ALL', activeOnly: false }
const KINDS: QueryKinds<MatrixQueryState> = { activeOnly: 'boolean' }

const { state, apply } = useQuerySync<MatrixQueryState>(DEFAULTS, KINDS)

const toast = useToast()

const RANGES: { value: MatrixRange; label: string }[] = [
  { value: 'WEEK', label: '本周' },
  { value: 'MONTH', label: '本月' },
  { value: 'ALL', label: '全部' },
]

/**
 * 来源下拉与任务池共用 meta 全量（含已停用），不写死三类（AC-5）：
 * 启用项按 sort 在前，停用项在后并标注「（已停用）」，历史任务的来源仍可筛。
 */
const sourceTypes = ref<SourceTypeOption[]>([])
const sourceOptions = computed<SourceOption[]>(() => buildSourceOptions(sourceTypes.value))

const view = ref<MatrixView | null>(null)
const loading = ref(false)
const error = ref<ApiError | null>(null)
let seq = 0

const range = computed<MatrixRange>(() =>
  state.value.range === 'WEEK' || state.value.range === 'MONTH' ? state.value.range : 'ALL',
)

/** 周/月口径按截止日期过滤，任务池没有这个筛选项，下钻会多出区间外的任务 */
const rangeScoped = computed(() => range.value !== 'ALL')

type SortKey = MatrixBucketKey | 'total' | 'completionRate'
const sortKey = ref<SortKey | null>(null)
const sortDir = ref<'asc' | 'desc'>('desc')

const rows = computed<MatrixRow[]>(() => {
  const source = view.value?.rows
  if (!Array.isArray(source)) return []
  if (!sortKey.value) return source
  const key = sortKey.value
  const direction = sortDir.value === 'asc' ? 1 : -1
  return [...source].sort((a, b) => {
    const left = key === 'completionRate' ? (a.completionRate ?? -1) : Number(a[key])
    const right = key === 'completionRate' ? (b.completionRate ?? -1) : Number(b[key])
    if (left === right) return a.name.localeCompare(b.name, 'zh-Hans-CN')
    return left < right ? -direction : direction
  })
})

const totals = computed<MatrixRow | null>(() => view.value?.totals ?? null)

async function load(): Promise<void> {
  const current = ++seq
  loading.value = true
  error.value = null
  try {
    const next = await matrixApi.load({
      sourceType: state.value.source || undefined,
      range: range.value,
      includeDisabled: state.value.activeOnly ? false : true,
    })
    if (current !== seq) return
    view.value = next
  } catch (err) {
    if (current !== seq) return
    const apiError = toApiError(err)
    // 旧链接 / 手改 URL 带来未知来源编码 → 400：提示并去掉该筛选项重取，不白屏（DESIGN §3.7）
    if (apiError.code === 'SOURCE_TYPE_INVALID' && state.value.source) {
      state.value.source = ''
      toast.warning('来源类型不合法，已按全部来源展示')
      return
    }
    error.value = apiError
    view.value = null
  } finally {
    if (current === seq) loading.value = false
  }
}

onMounted(() => {
  void load()
  void loadSourceOptions()
})

async function loadSourceOptions(): Promise<void> {
  try {
    const meta = await metaApi.options()
    sourceTypes.value = meta.sourceTypes ?? []
  } catch {
    /* 选项加载失败只影响来源下拉，不阻塞矩阵本体 */
  }
}
watch(
  () => [state.value.source, range.value, state.value.activeOnly],
  () => {
    apply()
    void load()
  },
)

function setRange(value: MatrixRange): void {
  state.value.range = value
}

function setSource(value: string): void {
  state.value.source = (value as SourceType | '') ?? ''
}

function toggleSort(key: SortKey): void {
  if (sortKey.value === key) {
    sortDir.value = sortDir.value === 'desc' ? 'asc' : 'desc'
    return
  }
  sortKey.value = key
  sortDir.value = 'desc'
}

function ariaSort(key: SortKey): 'ascending' | 'descending' | 'none' {
  if (sortKey.value !== key) return 'none'
  return sortDir.value === 'asc' ? 'ascending' : 'descending'
}

function drillTo(row: MatrixRow, bucket: MatrixBucketKey) {
  return matrixDrilldown(row, bucket, { sourceType: state.value.source || undefined })
}

function cellLabel(row: MatrixRow, bucket: MatrixBucketKey): string {
  const label = MATRIX_BUCKETS.find((item) => item.key === bucket)?.label ?? bucket
  return `${row.name} 的 ${label}`
}

function ratePercent(rate: number | null): number {
  if (rate === null || Number.isNaN(rate)) return 0
  return Math.max(0, Math.min(100, rate <= 1 ? rate * 100 : rate))
}
</script>

<template>
  <section class="page">
    <PageHeader
      title="人维度闭环矩阵"
      description="逾期任务只计入「逾期」列，不重复计入原状态列（口径说明）。"
    />

    <div class="toolbar">
      <AppSelect
        :model-value="state.source"
        :options="sourceOptions"
        label="来源类型"
        size="compact"
        class="toolbar__source"
        @update:model-value="setSource"
      />
      <div class="segmented" role="group" aria-label="统计区间">
        <button
          v-for="item in RANGES"
          :key="item.value"
          type="button"
          class="segmented__item"
          :class="{ 'is-active': range === item.value }"
          :aria-pressed="range === item.value"
          @click="setRange(item.value)"
        >
          {{ item.label }}
        </button>
      </div>
      <label class="switch">
        <input v-model="state.activeOnly" type="checkbox" class="switch__input" />
        <span class="switch__track" aria-hidden="true"><span class="switch__dot" /></span>
        <span class="switch__label">仅显示在职</span>
      </label>
    </div>

    <p v-if="rangeScoped" class="range-note">
      当前按截止日期筛选（{{ view?.rangeFrom ?? '—' }} 至 {{ view?.rangeTo ?? '—' }}）。下钻沿用「负责人 + 状态」口径，
      任务池暂不支持截止日区间筛选，区间外的任务也会出现在结果里。
    </p>

    <ErrorState
      v-if="error"
      title="矩阵加载失败"
      :description="error.message"
      :trace-id="error.traceId ?? undefined"
      @retry="load"
    />

    <div v-else class="matrix-wrap">
      <SkeletonTable v-if="loading && !view" :rows="6" :columns="9" />
      <table v-else-if="rows.length" class="matrix">
        <colgroup>
          <col style="width: 200px" />
          <col v-for="bucket in MATRIX_BUCKETS" :key="bucket.key" style="width: 88px" />
          <col style="width: 88px" />
          <col style="width: 140px" />
          <col style="width: 72px" />
        </colgroup>
        <thead>
          <tr>
            <th scope="col" class="col-owner">负责人</th>
            <th
              v-for="bucket in MATRIX_BUCKETS"
              :key="bucket.key"
              scope="col"
              :aria-sort="ariaSort(bucket.key)"
              class="col-num"
              :class="{ 'col-danger': bucket.danger }"
            >
              <button type="button" class="sort" @click="toggleSort(bucket.key)">
                {{ bucket.label }}
              </button>
            </th>
            <th scope="col" :aria-sort="ariaSort('total')" class="col-num">
              <button type="button" class="sort" @click="toggleSort('total')">合计</button>
            </th>
            <th scope="col" :aria-sort="ariaSort('completionRate')" class="col-rate">
              <button type="button" class="sort" @click="toggleSort('completionRate')">完成率</button>
            </th>
            <th scope="col" class="col-num" :aria-sort="ariaSort('cancelled')">
              <button type="button" class="sort" @click="toggleSort('cancelled')">已取消</button>
            </th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="row in rows" :key="row.userId" :class="{ 'is-disabled': row.userStatus === 'DISABLED' }">
            <th scope="row" class="col-owner">
              <span class="owner-name">{{ row.name }}</span>
              <span v-if="row.userStatus === 'DISABLED'" class="owner-tag">已停用</span>
            </th>
            <td
              v-for="bucket in MATRIX_BUCKETS"
              :key="bucket.key"
              class="col-num"
              :class="{ 'cell--overdue': bucket.danger && row[bucket.key] > 0 }"
            >
              <MatrixCell
                :value="row[bucket.key]"
                :to="drillTo(row, bucket.key)"
                :danger="Boolean(bucket.danger)"
                :label="cellLabel(row, bucket.key)"
              />
            </td>
            <td class="col-num cell--total">
              <span class="total-value num">{{ formatNumber(row.total) }}</span>
            </td>
            <td class="col-rate">
              <div class="rate">
                <span class="rate__track" aria-hidden="true">
                  <span class="rate__bar" :style="{ width: `${ratePercent(row.completionRate)}%` }" />
                </span>
                <span class="rate__text num">{{ formatPercent(row.completionRate) }}</span>
              </div>
            </td>
            <td class="col-num">
              <MatrixCell :value="row.cancelled" :to="drillTo(row, 'cancelled')" :label="cellLabel(row, 'cancelled')" />
            </td>
          </tr>
        </tbody>
        <tfoot v-if="totals">
          <tr class="totals">
            <th scope="row" class="col-owner">合计</th>
            <td v-for="bucket in MATRIX_BUCKETS" :key="bucket.key" class="col-num">
              <span class="total-value num" :class="{ 'total-value--danger': bucket.danger && totals[bucket.key] > 0 }">
                {{ formatNumber(totals[bucket.key]) }}
              </span>
            </td>
            <td class="col-num"><span class="total-value num">{{ formatNumber(totals.total) }}</span></td>
            <td class="col-rate">
              <div class="rate">
                <span class="rate__track" aria-hidden="true">
                  <span class="rate__bar" :style="{ width: `${ratePercent(totals.completionRate)}%` }" />
                </span>
                <span class="rate__text num">{{ formatPercent(totals.completionRate) }}</span>
              </div>
            </td>
            <td class="col-num"><span class="total-value num">{{ formatNumber(totals.cancelled) }}</span></td>
          </tr>
        </tfoot>
      </table>
      <div v-else-if="!loading" class="empty">
        <EmptyState
          title="还没有任务数据"
          description="任务池里有未取消的任务后，这里会按人聚合出待开始、进行中、逾期与已完成。"
        />
        <RouterLink class="empty__link" to="/tasks">去任务池</RouterLink>
      </div>
    </div>

    <p class="footnote">
      口径：五个数字列互斥（逾期已从原状态列移出）；合计 = 五列之和，不含已取消；完成率 = 已完成 ÷ 合计，合计为 0 时显示「—」。
      0 不可下钻。
    </p>
  </section>
</template>

<style scoped>
.page {
  display: flex;
  flex-direction: column;
  gap: var(--sp-6);
  padding: var(--sp-6);
}
.toolbar {
  display: flex;
  align-items: flex-end;
  gap: var(--sp-4);
  flex-wrap: wrap;
}
.toolbar__source {
  width: 160px;
}
.segmented {
  display: inline-flex;
  border: 1px solid var(--c-line);
  border-radius: var(--r-md);
  overflow: hidden;
  background: var(--c-surface);
}
.segmented__item {
  height: 32px;
  padding: 0 var(--sp-3);
  border: 0;
  background: transparent;
  font-size: var(--fs-body-sm);
  color: var(--c-ink-600);
  cursor: pointer;
  transition:
    background-color var(--t-hover) var(--ease),
    color var(--t-hover) var(--ease);
}
.segmented__item + .segmented__item {
  border-left: 1px solid var(--c-line);
}
.segmented__item:hover {
  background: var(--c-surface-hover);
}
.segmented__item.is-active {
  background: var(--c-primary-soft);
  color: var(--c-primary);
  font-weight: 600;
}
.segmented__item:focus-visible {
  outline: 2px solid var(--c-primary);
  outline-offset: -2px;
}
.switch {
  display: inline-flex;
  align-items: center;
  gap: var(--sp-2);
  height: 32px;
  font-size: var(--fs-body-sm);
  color: var(--c-ink-600);
  cursor: pointer;
}
.switch__input {
  position: absolute;
  opacity: 0;
  width: 1px;
  height: 1px;
}
.switch__track {
  width: 36px;
  height: 20px;
  border-radius: var(--r-pill);
  background: var(--c-line);
  position: relative;
  transition: background-color var(--t-toggle) var(--ease);
}
.switch__dot {
  position: absolute;
  top: 2px;
  left: 2px;
  width: 16px;
  height: 16px;
  border-radius: 50%;
  background: var(--c-surface);
  box-shadow: var(--sh-1);
  transition: transform var(--t-toggle) var(--ease);
}
.switch__input:checked + .switch__track {
  background: var(--c-primary);
}
.switch__input:checked + .switch__track .switch__dot {
  transform: translateX(16px);
}
.switch__input:focus-visible + .switch__track {
  outline: 2px solid var(--c-primary);
  outline-offset: 2px;
}
.range-note {
  margin: calc(var(--sp-4) * -1) 0 0;
  font-size: var(--fs-caption);
  line-height: var(--lh-caption);
  color: var(--c-ink-500);
}
.matrix-wrap {
  border: 1px solid var(--c-line);
  border-radius: var(--r-lg);
  background: var(--c-surface);
  overflow-x: auto;
}
.matrix {
  width: 940px;
  border-collapse: separate;
  border-spacing: 0;
  table-layout: fixed;
}
.matrix thead th {
  position: sticky;
  top: 0;
  z-index: var(--z-thead);
  height: 40px;
  padding: 0 var(--sp-3);
  background: var(--c-surface-hover);
  border-bottom: 1px solid var(--c-line);
  font-size: var(--fs-caption);
  font-weight: 600;
  color: var(--c-ink-600);
  text-align: left;
  white-space: nowrap;
}
.col-num {
  text-align: right;
}
.matrix th.col-num,
.matrix td.col-num {
  padding: 0;
}
.matrix thead th.col-num {
  padding: 0 var(--sp-3);
}
.col-danger {
  color: var(--c-danger-text);
}
.sort {
  border: 0;
  background: transparent;
  font: inherit;
  color: inherit;
  cursor: pointer;
  padding: 0;
  height: 40px;
}
.sort:hover {
  color: var(--c-primary);
}
.sort:focus-visible {
  outline: 2px solid var(--c-primary);
  outline-offset: 2px;
}
.matrix tbody th,
.matrix tbody td {
  height: 48px;
  padding: 0 var(--sp-3);
  border-bottom: 1px solid var(--c-line-soft);
  font-size: var(--fs-body-sm);
  color: var(--c-ink-900);
  vertical-align: middle;
}
.matrix tbody tr {
  transition: background-color var(--t-hover) var(--ease);
}
.matrix tbody tr:hover {
  background: var(--c-surface-hover);
}
.col-owner {
  position: sticky;
  left: 0;
  z-index: 1;
  background: var(--c-surface);
  text-align: left;
  font-weight: 500;
  white-space: nowrap;
}
.matrix thead .col-owner {
  z-index: calc(var(--z-thead) + 1);
  background: var(--c-surface-hover);
}
.matrix tbody tr:hover .col-owner {
  background: var(--c-surface-hover);
}
.owner-name {
  font-weight: 500;
}
.owner-tag {
  margin-left: var(--sp-2);
  font-size: var(--fs-micro);
  color: var(--c-ink-500);
  border: 1px solid var(--c-line);
  border-radius: var(--r-sm);
  padding: 1px 4px;
}
.cell--overdue {
  background: var(--c-danger-soft);
}
.cell--total {
  background: var(--c-surface-hover);
}
.total-value {
  display: inline-block;
  padding: 0 var(--sp-3);
  font-weight: 600;
  font-variant-numeric: tabular-nums;
}
.total-value--danger {
  color: var(--c-danger-text);
}
.rate {
  display: flex;
  align-items: center;
  gap: var(--sp-2);
}
.rate__track {
  width: 64px;
  height: 6px;
  border-radius: var(--r-pill);
  background: var(--c-line-soft);
  overflow: hidden;
  flex: none;
}
.rate__bar {
  display: block;
  height: 100%;
  background: var(--c-primary);
  border-radius: inherit;
}
.rate__text {
  font-size: var(--fs-caption);
  color: var(--c-ink-600);
  font-variant-numeric: tabular-nums;
}
.matrix tfoot th,
.matrix tfoot td {
  height: 48px;
  padding: 0 var(--sp-3);
  background: var(--c-surface-hover);
  border-top: 1px solid var(--c-line);
  font-size: var(--fs-body-sm);
  font-weight: 600;
  color: var(--c-ink-900);
  vertical-align: middle;
}
.matrix tfoot td.col-num {
  padding: 0;
}
.empty {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: var(--sp-3);
  padding: var(--sp-10) var(--sp-6);
}
.empty__link {
  font-size: var(--fs-body-sm);
  color: var(--c-primary);
}
.footnote {
  margin: 0;
  font-size: var(--fs-caption);
  line-height: var(--lh-caption);
  color: var(--c-ink-500);
}
</style>
