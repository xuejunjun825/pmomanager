<script setup lang="ts">
/**
 * 全局看板（AC-16 / §4.9）。
 * 6 个 KPI、未完成构成条与督办焦点列表来自同一个 /api/dashboard 响应，筛选项一次改动即整体刷新。
 * 管理层（EXEC）只保留改派/改期/催办——验收入口既不渲染，接口层同样拒绝。
 */
import { computed, onMounted, ref, watch } from 'vue'
import PageHeader from '@/components/ui/PageHeader.vue'
import KpiCard from '@/components/ui/KpiCard.vue'
import DataTable from '@/components/ui/DataTable.vue'
import AppSelect from '@/components/ui/AppSelect.vue'
import AppInput from '@/components/ui/AppInput.vue'
import AppDatePicker from '@/components/ui/AppDatePicker.vue'
import AppButton from '@/components/ui/AppButton.vue'
import Modal from '@/components/ui/Modal.vue'
import StatusBadge from '@/components/ui/StatusBadge.vue'
import OverduePill from '@/components/ui/OverduePill.vue'
import SourceTag from '@/components/ui/SourceTag.vue'
import UrgeButton from '@/components/task/UrgeButton.vue'
import ErrorState from '@/components/ui/ErrorState.vue'
import EmptyState from '@/components/ui/EmptyState.vue'
import { useQuerySync, type QueryKinds } from '@/composables/useQuerySync'
import { useToast } from '@/composables/useToast'
import { dashboardApi } from '@/api/dashboard'
import { metaApi } from '@/api/meta'
import { taskApi } from '@/api/tasks'
import { toApiError, type ApiError } from '@/api/http'
import { formatDate, formatNumber, formatPercent } from '@/utils/format'
import { isFinishedStatus } from '@/types/enums'
import type { SourceType } from '@/types/enums'
import type { DashboardView, SourceTypeOption, TaskRow, UrgeResult, UserOption } from '@/types/api'
import type { TableColumn } from '@/components/ui/DataTable.vue'
import type { SelectOption } from '@/components/ui/AppSelect.vue'

const toast = useToast()

interface DashboardQueryState {
  owner: number | ''
  source: SourceType | ''
  meeting: number | ''
}

const DEFAULTS: DashboardQueryState = { owner: '', source: '', meeting: '' }
const KINDS: QueryKinds<DashboardQueryState> = { owner: 'number', meeting: 'number' }

const { state, apply } = useQuerySync<DashboardQueryState>(DEFAULTS, KINDS)

const view = ref<DashboardView | null>(null)
const loading = ref(false)
const error = ref<ApiError | null>(null)
const expanded = ref(false)
let seq = 0

const owners = ref<UserOption[]>([])
const meetings = ref<{ id: number; title: string }[]>([])
/** 来源选项来自 meta 全量（含已停用，历史任务仍要能筛），不本地写死三类（AC-5） */
const sourceTypes = ref<SourceTypeOption[]>([])

const ownerOptions = computed<SelectOption[]>(() => [
  { label: '全部负责人', value: '' },
  ...owners.value.map((user) => ({ label: user.name, value: String(user.id) })),
])
const meetingOptions = computed<SelectOption[]>(() => [
  { label: '全部会议', value: '' },
  ...meetings.value.map((meeting) => ({ label: meeting.title, value: String(meeting.id) })),
])
const sourceOptions = computed<SelectOption[]>(() => [
  { label: '全部来源', value: '' },
  ...sourceTypes.value.map((item) => ({ label: item.label, value: item.value })),
])

const kpis = computed(() => view.value?.kpis ?? null)
const focusRows = computed<TaskRow[]>(() => view.value?.focus ?? [])
/** DataTable 泛型约束是 Record<string, unknown>，接口类型过不去；仅在此处降级，插槽里立刻收窄 */
const tableRows = computed(() => focusRows.value as unknown as Record<string, unknown>[])
function asTask(row: Record<string, unknown>): TaskRow {
  return row as unknown as TaskRow
}
const focusTotal = computed(() => view.value?.focusTotal ?? focusRows.value.length)
const canExpand = computed(() => focusTotal.value > focusRows.value.length)

const columns: TableColumn[] = [
  { key: 'title', label: '任务' },
  { key: 'owner', label: '负责人', width: '112px' },
  { key: 'due', label: '截止', width: '120px' },
  { key: 'status', label: '状态', width: '96px' },
  { key: 'overdue', label: '逾期', width: '104px' },
  { key: 'actions', label: '操作', width: '256px', align: 'right' },
]

/** 未完成构成：逾期 / 待验收 / 待开始+进行中，三者之和等于未完成（互斥口径） */
const segments = computed(() => {
  const k = kpis.value
  if (!k || k.unfinished <= 0) return []
  const active = Math.max(0, k.unfinished - k.overdue - k.pendingAccept)
  return [
    { key: 'overdue', label: '逾期', value: k.overdue, color: 'var(--c-danger)' },
    { key: 'accept', label: '待验收', value: k.pendingAccept, color: 'var(--c-warn)' },
    { key: 'active', label: '待开始 + 进行中', value: active, color: 'var(--c-primary)' },
  ].filter((segment) => segment.value > 0)
})

function segmentWidth(value: number): string {
  const base = kpis.value?.unfinished ?? 0
  if (base <= 0) return '0%'
  return `${(value / base) * 100}%`
}

async function load(): Promise<void> {
  const current = ++seq
  loading.value = true
  error.value = null
  try {
    const limit = expanded.value ? Math.min(Math.max(focusTotal.value, 10), 200) : 10
    const next = await dashboardApi.load({
      ownerId: state.value.owner === '' ? undefined : state.value.owner,
      sourceType: state.value.source || undefined,
      meetingId: state.value.meeting === '' ? undefined : state.value.meeting,
      focusLimit: limit,
    })
    if (current !== seq) return
    view.value = next
  } catch (err) {
    if (current !== seq) return
    error.value = toApiError(err)
    view.value = null
  } finally {
    if (current === seq) loading.value = false
  }
}

onMounted(async () => {
  void load()
  try {
    const meta = await metaApi.options()
    owners.value = meta.owners
    meetings.value = meta.meetings
    sourceTypes.value = meta.sourceTypes ?? []
  } catch {
    /* 选项加载失败只影响筛选下拉，不阻塞看板本体 */
  }
})

watch(
  () => [state.value.owner, state.value.source, state.value.meeting],
  () => {
    expanded.value = false
    apply()
    void load()
  },
)

function setOwner(value: string): void {
  state.value.owner = value === '' ? '' : Number(value)
}
function setSource(value: string): void {
  state.value.source = (value as SourceType | '') ?? ''
}
function setMeeting(value: string): void {
  state.value.meeting = value === '' ? '' : Number(value)
}

function toggleExpand(): void {
  expanded.value = !expanded.value
  void load()
}

/* ---------------------------------------------------- 行内催办 / 改派 / 改期 */

/**
 * 催办结果的渠道反馈由 UrgeButton 自己 toast（它持有 result.channelLabel 的唯一真源），
 * 这里只把次数与时间回填到行上，避免同一次催办弹两条 toast。
 * 行上保留「已催办 · 刚刚 / 已于 HH:mm 催办过」作为常驻反馈（DESIGN §1.4 持久信息）。
 */
function onUrged(task: TaskRow, result: UrgeResult): void {
  task.urgeCount = result.urgeCount
  task.lastUrgedAt = result.lastUrgedAt
}

type DialogKind = 'reassign' | 'reschedule'
const dialog = ref<{ kind: DialogKind; task: TaskRow } | null>(null)
const nextOwner = ref('')
const nextDueDate = ref('')
const reason = ref('')
const submitting = ref(false)
const dialogError = ref('')
const ownerError = ref('')
const dateError = ref('')

const reassignOptions = computed<SelectOption[]>(() =>
  owners.value
    .filter((user) => user.status === 'ACTIVE' && user.id !== dialog.value?.task.owner.id)
    .map((user) => ({ label: user.name, value: String(user.id) })),
)

function openDialog(kind: DialogKind, task: TaskRow): void {
  dialog.value = { kind, task }
  nextOwner.value = ''
  nextDueDate.value = task.dueDate
  reason.value = ''
  dialogError.value = ''
  ownerError.value = ''
  dateError.value = ''
}

function closeDialog(): void {
  if (submitting.value) return
  dialog.value = null
}

async function submitDialog(): Promise<void> {
  const current = dialog.value
  if (!current) return
  ownerError.value = ''
  dateError.value = ''
  dialogError.value = ''
  const trimmed = reason.value.trim()
  if (current.kind === 'reassign' && !nextOwner.value) {
    ownerError.value = '请选择新的负责人'
    return
  }
  if (current.kind === 'reschedule' && !nextDueDate.value) {
    dateError.value = '请选择新的截止日期'
    return
  }
  if (trimmed.length < 2) {
    dialogError.value = '请填写原因（至少 2 个字）'
    return
  }
  submitting.value = true
  try {
    const updated =
      current.kind === 'reassign'
        ? await taskApi.reassign(current.task.id, Number(nextOwner.value), trimmed)
        : await taskApi.reschedule(current.task.id, nextDueDate.value, trimmed)
    toast.success(current.kind === 'reassign' ? '已改派，事件已留痕' : '已改期，事件已留痕')
    dialog.value = null
    const row = focusRows.value.find((item) => item.id === updated.id)
    if (row) {
      Object.assign(row, updated)
    }
    void load()
  } catch (err) {
    const apiError = toApiError(err)
    dialogError.value = apiError.fieldError('reason') || apiError.message
  } finally {
    submitting.value = false
  }
}

function canUrge(task: TaskRow): boolean {
  return !isFinishedStatus(task.status)
}
function urgeReason(task: TaskRow): string {
  return isFinishedStatus(task.status) ? '任务已结束' : ''
}
</script>

<template>
  <section class="page">
    <PageHeader title="工作台" description="逾期与待验收的全局视图，焦点列表按逾期天数与截止日期排序。" />

    <div class="toolbar">
      <AppSelect
        :model-value="state.owner === '' ? '' : String(state.owner)"
        :options="ownerOptions"
        label="负责人"
        size="compact"
        class="toolbar__select"
        @update:model-value="setOwner"
      />
      <AppSelect
        :model-value="state.source"
        :options="sourceOptions"
        label="来源"
        size="compact"
        class="toolbar__select"
        @update:model-value="setSource"
      />
      <AppSelect
        :model-value="state.meeting === '' ? '' : String(state.meeting)"
        :options="meetingOptions"
        label="会议"
        size="compact"
        class="toolbar__select"
        @update:model-value="setMeeting"
      />
    </div>

    <ErrorState
      v-if="error"
      title="看板加载失败"
      :description="error.message"
      :trace-id="error.traceId ?? undefined"
      @retry="load"
    />

    <template v-else>
      <div class="kpis" :class="{ 'is-loading': loading && !view }">
        <template v-if="kpis">
          <KpiCard label="任务总数" :value="formatNumber(kpis.total)" sub="不含已取消" />
          <KpiCard
            label="未完成"
            :value="formatNumber(kpis.unfinished)"
            :sub="kpis.total ? `占任务总数 ${formatPercent(kpis.unfinished / kpis.total)}` : '暂无任务'"
          />
          <KpiCard
            label="逾期"
            :value="formatNumber(kpis.overdue)"
            :sub="kpis.unfinished ? `占未完成 ${formatPercent(kpis.overdue / kpis.unfinished)}` : '暂无未完成任务'"
            :danger="kpis.overdue > 0"
          />
          <KpiCard label="逾期率" :value="formatPercent(kpis.overdueRate)" sub="逾期 ÷ 未完成" />
          <KpiCard label="24 小时内到期" :value="formatNumber(kpis.dueSoon24h)" sub="截止日期为明天的未完成任务" />
          <KpiCard label="待验收" :value="formatNumber(kpis.pendingAccept)" sub="等待派发人验收" />
        </template>
        <template v-else>
          <div v-for="index in 6" :key="index" class="kpi-skeleton sk-block" />
        </template>
      </div>

      <section class="panel">
        <header class="panel__head">
          <h2 class="panel__title">未完成构成</h2>
          <span class="panel__hint">逾期、待验收、待开始/进行中三段互斥，合计等于未完成 {{ formatNumber(kpis?.unfinished) }} 条</span>
        </header>
        <div v-if="segments.length" class="bar" role="img" :aria-label="`未完成 ${kpis?.unfinished} 条的构成`">
          <span
            v-for="segment in segments"
            :key="segment.key"
            class="bar__seg"
            :style="{ width: segmentWidth(segment.value), background: segment.color }"
            :title="`${segment.label} ${segment.value} 条`"
          />
        </div>
        <p v-else class="bar__empty">暂无未完成任务。</p>
        <ul v-if="segments.length" class="legend">
          <li v-for="segment in segments" :key="segment.key" class="legend__item">
            <span class="legend__dot" :style="{ background: segment.color }" aria-hidden="true" />
            <span class="legend__label">{{ segment.label }}</span>
            <span class="legend__value num">{{ formatNumber(segment.value) }}</span>
          </li>
        </ul>
      </section>

      <section class="panel">
        <header class="panel__head">
          <div>
            <h2 class="panel__title">督办焦点</h2>
            <p class="panel__hint">排序：逾期天数降序 → 截止日期升序；管理层可改派、改期、催办，验收由派发人处理。</p>
          </div>
          <span class="panel__count num">{{ formatNumber(focusTotal) }} 条未完成</span>
        </header>

        <DataTable
          v-if="focusRows.length"
          :columns="columns"
          :rows="tableRows"
          row-key="id"
          :loading="loading && !view"
          :dense="false"
        >
          <template #cell-title="{ row }">
            <RouterLink class="title-link" :to="`/tasks/${asTask(row).id}`">{{ asTask(row).title }}</RouterLink>
            <SourceTag
              :code="asTask(row).source.type"
              :name="asTask(row).source.typeLabel"
              :color="asTask(row).source.color"
              :soft-color="asTask(row).source.softColor"
              :border-color="asTask(row).source.borderColor"
              :meeting-title="asTask(row).source.meetingTitle"
              class="title-src"
            />
          </template>
          <template #cell-owner="{ row }">{{ asTask(row).owner.name }}</template>
          <template #cell-due="{ row }">
            <span class="num">{{ formatDate(asTask(row).dueDate) }}</span>
          </template>
          <template #cell-status="{ row }">
            <StatusBadge :status="asTask(row).status" size="sm" />
          </template>
          <template #cell-overdue="{ row }">
            <OverduePill v-if="asTask(row).isOverdue" :days="asTask(row).overdueDays" />
            <OverduePill v-else-if="asTask(row).dueSoon" :days="0" variant="due-soon" />
            <span v-else class="muted">—</span>
          </template>
          <template #cell-actions="{ row }">
            <div class="actions">
              <UrgeButton
                :task-id="asTask(row).id"
                :owner-name="asTask(row).owner.name"
                :last-urged-at="asTask(row).lastUrgedAt ?? null"
                :urge-count="asTask(row).urgeCount"
                :can-urge="canUrge(asTask(row))"
                :disabled-reason="urgeReason(asTask(row))"
                @urged="onUrged(asTask(row), $event)"
              />
              <AppButton variant="ghost" size="compact" @click="openDialog('reassign', asTask(row))">改派</AppButton>
              <AppButton variant="ghost" size="compact" @click="openDialog('reschedule', asTask(row))">改期</AppButton>
            </div>
          </template>
        </DataTable>
        <div v-else-if="!loading" class="panel__empty">
          <EmptyState title="没有需要督办的任务" description="当前筛选条件下所有任务都已完成或已取消。" />
        </div>

        <div v-if="canExpand" class="panel__more">
          <AppButton variant="secondary" size="compact" :loading="loading" @click="toggleExpand">
            {{ expanded ? '收起' : `展开全部（${formatNumber(focusTotal)}）` }}
          </AppButton>
        </div>
      </section>
    </template>

    <Modal
      :open="dialog !== null"
      :title="dialog?.kind === 'reassign' ? '改派任务' : '调整截止日期'"
      :description="dialog ? `任务：${dialog.task.title}` : ''"
      size="md"
      @close="closeDialog"
    >
      <form class="form" @submit.prevent="submitDialog">
        <AppSelect
          v-if="dialog?.kind === 'reassign'"
          v-model="nextOwner"
          :options="reassignOptions"
          label="新负责人"
          placeholder="请选择负责人"
          required
          :invalid="!!ownerError"
          :error="ownerError"
        />
        <AppDatePicker
          v-else
          v-model="nextDueDate"
          label="新截止日期"
          required
          :invalid="!!dateError"
          :error="dateError"
        />
        <AppInput
          v-model="reason"
          label="变更原因"
          placeholder="例如：负责人休假，转交同事继续跟进"
          required
          :maxlength="200"
          :invalid="!!dialogError"
          hint="原因会写入任务事件，供追溯（2–200 字）"
        />
        <p v-if="dialogError" class="form__error" role="alert">{{ dialogError }}</p>
        <div class="form__foot">
          <AppButton variant="secondary" :disabled="submitting" @click="closeDialog">取消</AppButton>
          <AppButton type="submit" :loading="submitting" loading-text="提交中">确认</AppButton>
        </div>
      </form>
    </Modal>
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
.toolbar__select {
  width: 168px;
}
.kpis {
  display: grid;
  grid-template-columns: repeat(6, minmax(0, 1fr));
  gap: var(--sp-4);
}
.kpi-skeleton {
  height: 104px;
  border-radius: var(--r-lg);
}
.panel {
  border: 1px solid var(--c-line);
  border-radius: var(--r-lg);
  background: var(--c-surface);
  padding: var(--sp-5);
  display: flex;
  flex-direction: column;
  gap: var(--sp-4);
}
.panel__head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: var(--sp-4);
}
.panel__title {
  margin: 0;
  font-size: var(--fs-h3);
  font-weight: 600;
  color: var(--c-ink-900);
}
.panel__hint {
  margin: 4px 0 0;
  font-size: var(--fs-caption);
  line-height: var(--lh-caption);
  color: var(--c-ink-500);
}
.panel__count {
  font-size: var(--fs-body-sm);
  color: var(--c-ink-600);
  font-variant-numeric: tabular-nums;
  white-space: nowrap;
}
.bar {
  display: flex;
  height: 8px;
  border-radius: var(--r-pill);
  overflow: hidden;
  background: var(--c-line-soft);
}
.bar__seg {
  height: 100%;
  transition: width var(--t-panel) var(--ease);
}
.bar__empty {
  margin: 0;
  font-size: var(--fs-body-sm);
  color: var(--c-ink-500);
}
.legend {
  display: flex;
  flex-wrap: wrap;
  gap: var(--sp-5);
  margin: 0;
  padding: 0;
  list-style: none;
}
.legend__item {
  display: inline-flex;
  align-items: center;
  gap: var(--sp-2);
  font-size: var(--fs-caption);
  color: var(--c-ink-600);
}
.legend__dot {
  width: 8px;
  height: 8px;
  border-radius: 2px;
}
.legend__value {
  font-weight: 600;
  color: var(--c-ink-900);
  font-variant-numeric: tabular-nums;
}
.title-link {
  color: var(--c-ink-900);
  font-weight: 500;
  text-decoration: none;
}
.title-link:hover {
  color: var(--c-primary);
  text-decoration: underline;
}
.title-link:focus-visible {
  outline: 2px solid var(--c-primary);
  outline-offset: 2px;
  border-radius: var(--r-sm);
}
.title-src {
  margin-left: var(--sp-2);
}
.actions {
  display: flex;
  align-items: center;
  justify-content: flex-end;
  gap: var(--sp-1);
  white-space: nowrap;
}
.muted {
  color: var(--c-ink-400);
}
.panel__empty {
  padding: var(--sp-8) 0;
}
.panel__more {
  display: flex;
  justify-content: center;
}
.form {
  display: flex;
  flex-direction: column;
  gap: var(--sp-4);
}
.form__error {
  margin: 0;
  font-size: var(--fs-caption);
  color: var(--c-danger-text);
}
.form__foot {
  display: flex;
  justify-content: flex-end;
  gap: var(--sp-3);
}
@media (max-width: 1439px) {
  .kpis {
    grid-template-columns: repeat(3, minmax(0, 1fr));
  }
}
@media (max-width: 1199px) {
  .kpis {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
}
</style>
