<script setup lang="ts">
/**
 * 进度甘特（DESIGN §3.1 / AC-10 ~ AC-12）。
 *
 * 三条铁律：
 * 1. 每个交互只发 1 次请求（seq 丢弃过期响应）；chip 筛选在已返回的 300 条内本地完成。
 * 2. 逾期 / 风险 / 起止全部是接口字段，页面只做像素与分组，没有任何日期差。
 * 3. 档位、筛选、折叠存在 useGanttView 的模块级单例里，进任务详情再回来不丢。
 */
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import PageHeader from '@/components/ui/PageHeader.vue'
import AppButton from '@/components/ui/AppButton.vue'
import AppSelect from '@/components/ui/AppSelect.vue'
import EmptyState from '@/components/ui/EmptyState.vue'
import GanttChart from '@/components/gantt/GanttChart.vue'
import GanttSummary from '@/components/gantt/GanttSummary.vue'
import { ganttApi } from '@/api/gantt'
import { metaApi } from '@/api/meta'
import { toApiError, type ApiError } from '@/api/http'
import { useToast } from '@/composables/useToast'
import { themeKey, useGanttView } from '@/composables/useGanttView'
import {
  defaultWindow,
  dayWidth,
  shiftWindow,
  todayString,
  type GanttWindow,
} from '@/composables/useGanttWindow'
import type { GanttQuickFilter, GanttTask, GanttThemeGroup, GanttView, GanttZoom, UserOption } from '@/types/api'

const router = useRouter()
const toast = useToast()
const view = useGanttView()

const ZOOMS: Array<{ value: GanttZoom; label: string; unit: string }> = [
  { value: 'day', label: '日', unit: '14 天' },
  { value: 'week', label: '周', unit: '12 周' },
  { value: 'month', label: '月', unit: '6 个月' },
]

const today = ref(todayString())
const win = ref<GanttWindow>(defaultWindow(view.zoom.value, today.value))
const data = ref<GanttView | null>(null)
const loading = ref(false)
const error = ref<ApiError | null>(null)
const ownerId = ref<number | ''>('')
const owners = ref<UserOption[]>([])
const canvas = ref<HTMLElement | null>(null)
const canvasWidth = ref(0)
const seeded = ref(false)
let seq = 0
let observer: ResizeObserver | null = null

const LEFT_COLUMN = 280

const dayW = computed(() => dayWidth(win.value, canvasWidth.value - LEFT_COLUMN))

const zoomUnit = computed(() => ZOOMS.find((item) => item.value === view.zoom.value)?.unit ?? '')

const ownerOptions = computed(() => [
  { label: '全部负责人', value: '' },
  ...owners.value.map((owner) => ({ label: owner.name, value: owner.id })),
])

function seedCollapse(groups: GanttThemeGroup[]): void {
  if (seeded.value) return
  seeded.value = true
  if (groups.length <= 12) return
  for (const group of groups) {
    if (group.riskCount === 0 && group.overdueCount === 0) view.collapsed.add(themeKey(group.themeId))
  }
}

async function load(): Promise<void> {
  const ticket = ++seq
  loading.value = true
  error.value = null
  try {
    const result = await ganttApi.load({
      from: win.value.from,
      to: win.value.to,
      ownerId: ownerId.value === '' ? undefined : ownerId.value,
      includeDone: view.showDone.value,
      onlyRisk: view.onlyRisk.value,
    })
    if (ticket !== seq) return
    data.value = result
    today.value = result.today
    seedCollapse(result.groups)
  } catch (cause) {
    if (ticket !== seq) return
    data.value = null
    error.value = toApiError(cause)
  } finally {
    if (ticket === seq) loading.value = false
  }
}

const predicate = computed<((task: GanttTask) => boolean) | null>(() => {
  switch (view.filter.value) {
    case 'overdue':
      return (task) => task.isOverdue
    case 'risk':
      return (task) => task.isAtRisk
    case 'inProgress':
      return (task) => task.status === 'IN_PROGRESS'
    case 'pendingAccept':
      return (task) => task.status === 'PENDING_ACCEPT'
    case 'done':
      return (task) => task.status === 'DONE'
    default:
      return null
  }
})

/** chip 只筛已返回的 300 条：主题/项目计数按筛后重算，图和数字对得上 */
const groups = computed<GanttThemeGroup[]>(() => {
  const source = data.value?.groups ?? []
  const match = predicate.value
  const keepDone = view.showDone.value
  return source
    .map((group) => {
      const projects = group.projects
        .map((project) => {
          const tasks = project.tasks.filter(
            (task) => (keepDone || task.status !== 'DONE') && (!match || match(task)),
          )
          return { ...project, tasks, taskCount: tasks.length }
        })
        .filter((project) => project.tasks.length > 0)
      const tasks = projects.flatMap((project) => project.tasks)
      return {
        ...group,
        projects,
        taskCount: tasks.length,
        overdueCount: tasks.filter((task) => task.isOverdue).length,
        riskCount: tasks.filter((task) => task.isAtRisk).length,
      }
    })
    .filter((group) => group.taskCount > 0)
})

const unassignedCount = computed(() => data.value?.summary.unassigned ?? 0)
const coverage = computed(() => {
  const total = data.value?.windowTotal ?? 0
  if (!total) return 100
  return Math.round(((total - unassignedCount.value) / total) * 100)
})

const emptyTitle = computed(() => {
  if (view.onlyRisk.value) return '当前窗口内无风险任务'
  if (view.filter.value) return '当前筛选没有任务'
  return '这个时间范围内没有任务'
})

const emptyDescription = computed(() =>
  view.onlyRisk.value || view.filter.value ? '点一下已选中的数字取消筛选，或翻到相邻时间窗。' : '换一个档位，或扩大范围到本季。',
)

function hasMatch(filter: GanttQuickFilter): boolean {
  const test: Record<string, (task: GanttTask) => boolean> = {
    overdue: (task) => task.isOverdue,
    risk: (task) => task.isAtRisk,
    inProgress: (task) => task.status === 'IN_PROGRESS',
    pendingAccept: (task) => task.status === 'PENDING_ACCEPT',
    done: (task) => task.status === 'DONE',
  }
  const hit = test[filter]
  if (!hit) return true
  return (data.value?.groups ?? []).some((group) =>
    group.projects.some((project) => project.tasks.some((task) => hit(task))),
  )
}

/** 0 命中时最多右移 3 屏找第一条，仍没有就给可读提示，不无限翻 */
async function locate(filter: GanttQuickFilter): Promise<void> {
  for (let step = 0; step < 3; step += 1) {
    win.value = shiftWindow(win.value, 1)
    await load()
    if (hasMatch(filter)) {
      toast.success('已定位到第 1 条')
      return
    }
  }
  toast.warning('当前时间窗内没有符合条件的任务')
}

async function pick(filter: GanttQuickFilter): Promise<void> {
  const next = view.filter.value === filter ? '' : filter
  if (!next) {
    view.filter.value = ''
    return
  }
  const needsDone = next === 'done' && !view.showDone.value
  const needsRiskOff = next !== 'overdue' && next !== 'risk' && view.onlyRisk.value
  view.filter.value = next
  if (needsDone) view.showDone.value = true
  if (needsRiskOff) view.onlyRisk.value = false
  if (needsDone || needsRiskOff) await load()
  if (!hasMatch(next)) await locate(next)
}

async function setOnlyRisk(value: boolean): Promise<void> {
  view.onlyRisk.value = value
  if (value) {
    view.filter.value = ''
    view.collapsed.clear()
  }
  await load()
}

async function setShowDone(value: boolean): Promise<void> {
  view.showDone.value = value
  if (!value && view.filter.value === 'done') view.filter.value = ''
  await load()
}

async function setZoom(zoom: GanttZoom): Promise<void> {
  if (view.zoom.value === zoom) return
  view.zoom.value = zoom
  win.value = defaultWindow(zoom, today.value)
  await load()
}

async function shift(direction: -1 | 1): Promise<void> {
  win.value = shiftWindow(win.value, direction)
  await load()
}

async function goToday(): Promise<void> {
  win.value = defaultWindow(view.zoom.value, today.value)
  await load()
}

async function expandQuarter(): Promise<void> {
  view.zoom.value = 'month'
  win.value = defaultWindow('month', today.value)
  await load()
}

async function onOwner(value: string | number | null): Promise<void> {
  ownerId.value = value === '' || value === null ? '' : Number(value)
  await load()
}

function openTask(id: number): void {
  void router.push(`/tasks/${id}`)
}

function measure(): void {
  canvasWidth.value = canvas.value?.clientWidth ?? 0
}

function observe(): void {
  measure()
  if (typeof ResizeObserver === 'undefined' || !canvas.value) return
  observer = new ResizeObserver(measure)
  observer.observe(canvas.value)
}

async function loadOwners(): Promise<void> {
  try {
    const options = await metaApi.options()
    owners.value = options.owners
  } catch {
    owners.value = []
  }
}

onMounted(() => {
  observe()
  window.addEventListener('resize', measure)
  void load()
  void loadOwners()
})

onBeforeUnmount(() => {
  observer?.disconnect()
  observer = null
  window.removeEventListener('resize', measure)
})
</script>

<template>
  <div class="gantt-page">
    <PageHeader title="进度甘特" description="按主题与项目看全局进度，逾期与风险同一口径">
      <template #actions>
        <div class="gantt-page__controls">
          <div class="seg" role="group" aria-label="时间档位">
            <button
              v-for="zoom in ZOOMS"
              :key="zoom.value"
              class="seg__btn"
              :class="{ 'seg__btn--on': view.zoom.value === zoom.value }"
              type="button"
              :aria-pressed="view.zoom.value === zoom.value"
              @click="setZoom(zoom.value)"
            >
              {{ zoom.label }}
            </button>
          </div>
          <AppButton variant="secondary" size="compact" aria-label="上一屏" @click="shift(-1)">← 上一屏</AppButton>
          <AppButton variant="secondary" size="compact" @click="goToday">回到今天</AppButton>
          <AppButton variant="secondary" size="compact" aria-label="下一屏" @click="shift(1)">下一屏 →</AppButton>
          <AppButton
            :variant="view.showDone.value ? 'primary' : 'secondary'"
            size="compact"
            :aria-pressed="view.showDone.value"
            @click="setShowDone(!view.showDone.value)"
          >
            显示已完成
          </AppButton>
          <AppSelect
            class="gantt-page__owner"
            :model-value="ownerId"
            :options="ownerOptions"
            size="compact"
            placeholder="全部负责人"
            @update:model-value="onOwner"
          />
        </div>
      </template>
    </PageHeader>

    <div v-if="data" class="gantt-page__summary">
      <GanttSummary
        :summary="data.summary"
        :active="view.filter.value"
        :only-risk="view.onlyRisk.value"
        :show-done="view.showDone.value"
        @pick="pick"
        @update:only-risk="setOnlyRisk"
      />
      <p class="gantt-page__window">
        <span class="num">{{ win.from }} ~ {{ win.to }}</span>
        <span>· {{ zoomUnit }}</span>
        <span>· 窗口内 {{ data.windowTotal }} 条</span>
      </p>
      <p v-if="unassignedCount" class="gantt-page__hint">
        未归主题 {{ unassignedCount }} 条（覆盖率 {{ coverage }}%），
        <button class="gantt-page__link" type="button" @click="router.push('/tasks?theme=none')">
          到任务池按「未归主题」补主题 →
        </button>
      </p>
    </div>

    <div ref="canvas" class="gantt-page__canvas">
      <GanttChart
        :groups="groups"
        :window="win"
        :collapsed="view.collapsed"
        :day-w="dayW"
        :loading="loading"
        :error="error"
        :truncated="data?.truncated ?? false"
        :window-total="data?.windowTotal ?? 0"
        :today="today"
        @toggle="view.toggle"
        @open="openTask"
        @retry="load"
      >
        <template #empty>
          <EmptyState :title="emptyTitle" :description="emptyDescription">
            <div class="gantt-page__empty-actions">
              <AppButton variant="primary" size="default" @click="expandQuarter">扩大范围（本季）</AppButton>
              <AppButton variant="secondary" size="default" @click="goToday">回到今天</AppButton>
            </div>
          </EmptyState>
        </template>
      </GanttChart>

      <ul class="legend" aria-label="图例">
        <li class="legend__item"><span class="legend__swatch legend__swatch--PENDING" />待开始</li>
        <li class="legend__item"><span class="legend__swatch legend__swatch--IN_PROGRESS" />进行中</li>
        <li class="legend__item"><span class="legend__swatch legend__swatch--PENDING_ACCEPT" />待验收</li>
        <li class="legend__item"><span class="legend__swatch legend__swatch--DONE" />已完成</li>
        <li class="legend__item"><span class="legend__swatch legend__swatch--CANCELLED" />已取消</li>
        <li class="legend__item"><span class="legend__swatch legend__swatch--overdue" />逾期（红描边）</li>
        <li class="legend__item"><span class="legend__cap" />按创建日推算起点</li>
        <li class="legend__item"><span class="legend__clamp" />截止超出窗口</li>
      </ul>
    </div>
  </div>
</template>

<style scoped>
.gantt-page {
  display: flex;
  flex-direction: column;
  gap: var(--sp-4);
}
.gantt-page__controls {
  display: flex;
  align-items: center;
  gap: var(--sp-2);
  flex-wrap: wrap;
  justify-content: flex-end;
}
.seg {
  display: inline-flex;
  padding: 2px;
  border: 1px solid var(--c-line);
  border-radius: var(--r-md);
  background: var(--c-surface);
}
.seg__btn {
  height: 26px;
  padding: 0 var(--sp-3);
  border: 0;
  border-radius: var(--r-sm);
  background: transparent;
  color: var(--c-ink-600);
  font-size: var(--fs-caption);
  cursor: pointer;
  transition: background var(--t-hover) var(--ease), color var(--t-hover) var(--ease);
}
.seg__btn:hover {
  color: var(--c-ink-900);
  background: var(--c-surface-hover);
}
.seg__btn:focus-visible {
  outline: none;
  box-shadow: var(--focus-ring);
}
.seg__btn--on {
  background: var(--c-primary-soft);
  color: var(--c-primary);
  font-weight: 600;
}
.gantt-page__owner {
  width: 148px;
}
.gantt-page__summary {
  display: flex;
  flex-direction: column;
  gap: var(--sp-2);
}
.gantt-page__window {
  display: flex;
  gap: var(--sp-2);
  margin: 0;
  padding: 0 var(--sp-1);
  font-size: var(--fs-micro);
  color: var(--c-ink-500);
}
.gantt-page__hint {
  margin: 0;
  padding: var(--sp-2) var(--sp-3);
  border: 1px solid var(--c-info-border);
  border-radius: var(--r-md);
  background: var(--c-info-soft);
  font-size: var(--fs-caption);
  color: var(--c-ink-700);
}
.gantt-page__link {
  padding: 0;
  border: 0;
  background: transparent;
  color: var(--c-primary);
  font: inherit;
  cursor: pointer;
  text-decoration: underline;
}
.gantt-page__link:focus-visible {
  outline: none;
  box-shadow: var(--focus-ring);
  border-radius: var(--r-sm);
}
.gantt-page__canvas {
  min-width: 0;
}
.gantt-page__empty-actions {
  display: flex;
  gap: var(--sp-2);
}
.legend {
  display: flex;
  flex-wrap: wrap;
  gap: var(--sp-2) var(--sp-4);
  margin: var(--sp-2) 0 0;
  padding: 0 var(--sp-1);
  list-style: none;
  font-size: var(--fs-micro);
  color: var(--c-ink-500);
}
.legend__item {
  display: inline-flex;
  align-items: center;
  gap: 6px;
}
.legend__swatch {
  width: 18px;
  height: 10px;
  border-radius: 2px;
  border: 1px solid var(--c-line);
  background: var(--c-surface-hover);
}
.legend__swatch--IN_PROGRESS {
  background: var(--c-info-soft);
  border-color: var(--c-info-border);
}
.legend__swatch--PENDING_ACCEPT {
  background: var(--c-warn-soft);
  border-color: var(--c-warn-border);
}
.legend__swatch--DONE {
  background: var(--c-success-soft);
  border-color: var(--c-success-border);
}
.legend__swatch--CANCELLED {
  background: transparent;
  border-style: dashed;
}
.legend__swatch--overdue {
  background: var(--c-surface);
  border: 1.5px solid var(--c-danger);
}
.legend__cap {
  width: 3px;
  height: 12px;
  background: repeating-linear-gradient(180deg, var(--c-ink-400) 0 2px, transparent 2px 4px);
}
.legend__clamp {
  width: 0;
  height: 0;
  border-top: 8px solid var(--c-danger);
  border-left: 8px solid transparent;
}
</style>
