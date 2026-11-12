<script setup lang="ts">
/**
 * 甘特画布（DESIGN §3.1 / §4.1）：sticky 双行表头 48 + 三态行 + 网格。
 * 左列 280 sticky，时间轴列宽 = 窗口天数 × dayW；逾期条由接口保证绕出窗口返回，
 * 这里只按 barGeometry 的像素落位，不做任何日期比较（AC-7）。
 */
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import type { ApiError } from '@/api/http'
import type { GanttProjectGroup, GanttTask, GanttThemeGroup } from '@/types/api'
import type { GanttWindow } from '@/composables/useGanttWindow'
import { LEFT_COLUMN, buildTicks, dayOffset, monthBands, windowDays } from '@/composables/useGanttWindow'
import { projectKey, themeKey } from '@/composables/useGanttView'
import ErrorState from '@/components/ui/ErrorState.vue'
import EmptyState from '@/components/ui/EmptyState.vue'
import GanttRow from '@/components/gantt/GanttRow.vue'

const props = defineProps<{
  groups: GanttThemeGroup[]
  window: GanttWindow
  collapsed: Set<string>
  dayW: number
  loading: boolean
  error: ApiError | null
  truncated: boolean
  windowTotal: number
  /** 今天（服务端口径），只用来画今天线 */
  today: string
}>()

const emit = defineEmits<{
  (event: 'toggle', key: string): void
  (event: 'open', id: number): void
  (event: 'retry'): void
  /** `.gantt__scroll` 的 clientWidth（已扣除纵向滚动条），供页面换算日宽 */
  (event: 'resize', width: number): void
}>()

interface VisibleRow {
  key: string
  kind: 'theme' | 'project' | 'task'
  item: GanttThemeGroup | GanttProjectGroup | GanttTask
  depth: number
  collapsed: boolean
  hasChildren: boolean
}

const ticks = computed(() => buildTicks(props.window, props.dayW))
const bands = computed(() => monthBands(props.window, props.dayW))
const totalWidth = computed(() => windowDays(props.window) * props.dayW)
const todayX = computed(() => dayOffset(props.today, props.window, props.dayW))

const rows = computed<VisibleRow[]>(() => {
  const list: VisibleRow[] = []
  for (const group of props.groups) {
    const tKey = themeKey(group.themeId)
    const themeCollapsed = props.collapsed.has(tKey)
    list.push({
      key: tKey,
      kind: 'theme',
      item: group,
      depth: 0,
      collapsed: themeCollapsed,
      hasChildren: group.projects.length > 0,
    })
    if (themeCollapsed) continue
    for (const project of group.projects) {
      const pKey = projectKey(group.themeId, project.projectId)
      const projectCollapsed = props.collapsed.has(pKey)
      list.push({
        key: pKey,
        kind: 'project',
        item: project,
        depth: 1,
        collapsed: projectCollapsed,
        hasChildren: project.tasks.length > 0,
      })
      if (projectCollapsed) continue
      for (const task of project.tasks) {
        list.push({ key: `task:${task.id}`, kind: 'task', item: task, depth: 2, collapsed: false, hasChildren: false })
      }
    }
  }
  return list
})

const skeletonRows = [44, 32, 40, 40, 40, 32, 40, 40, 40]

/**
 * 滚动视口宽度上报（AC-1/2/3）：`.gantt__scroll` 是 overflow:auto 的真实滚动容器，
 * 只有它的 clientWidth 扣除了纵向滚动条；按页面外壳宽算会让日档多出约 15px 而边缘横滚。
 * `.gantt__scroll` 只在有行时渲染，所以根节点与滚动容器都挂观察，行数变化后 nextTick 复测。
 */
const rootEl = ref<HTMLElement | null>(null)
const scrollEl = ref<HTMLElement | null>(null)
let observer: ResizeObserver | null = null

function reportWidth(): void {
  emit('resize', scrollEl.value?.clientWidth ?? 0)
}

function observeSizes(): void {
  if (typeof ResizeObserver === 'undefined') return
  if (!observer) observer = new ResizeObserver(reportWidth)
  else observer.disconnect()
  if (rootEl.value) observer.observe(rootEl.value)
  if (scrollEl.value) observer.observe(scrollEl.value)
  reportWidth()
}

onMounted(() => {
  void nextTick(observeSizes)
})

watch(
  () => rows.value.length,
  () => {
    void nextTick(observeSizes)
  },
)

// 骨架 / 错误 / 空态与有行态之间切换时 `.gantt__scroll` 会整体重挂，重新挂观察并上报
watch(scrollEl, () => {
  void nextTick(observeSizes)
})

onBeforeUnmount(() => {
  observer?.disconnect()
  observer = null
})
</script>

<template>
  <section ref="rootEl" class="gantt" aria-label="进度甘特图">
    <div v-if="loading" class="gantt__skeleton" aria-busy="true" aria-label="加载中">
      <div class="gantt__sk-head" />
      <div v-for="(height, index) in skeletonRows" :key="index" class="gantt__sk-row" :style="{ height: `${height}px` }" />
    </div>

    <ErrorState
      v-else-if="error"
      class="gantt__error"
      title="甘特图加载失败"
      :description="error.message || '窗口数据没有取到，请重试或稍后再看。'"
      :trace-id="error.traceId"
      @retry="emit('retry')"
    />

    <template v-else-if="!rows.length">
      <div class="gantt__empty">
        <slot name="empty">
          <EmptyState title="这个时间范围内没有任务" description="换一个档位或翻到上一屏看看。" />
        </slot>
      </div>
    </template>

    <template v-else>
      <p v-if="truncated" class="gantt__notice" role="status">
        窗口内共 {{ windowTotal }} 条，当前显示 300 条，请缩小时间范围或按负责人筛选
      </p>
      <div ref="scrollEl" class="gantt__scroll">
        <div class="gantt__grid" :style="{ width: `${LEFT_COLUMN + totalWidth}px` }">
          <div class="gantt__head" :style="{ gridTemplateColumns: `${LEFT_COLUMN}px 1fr` }">
            <div class="gantt__corner">主题 / 项目 / 任务</div>
            <div class="gantt__head-axis" :style="{ width: `${totalWidth}px` }">
              <div class="gantt__bands">
                <div v-for="(band, index) in bands" :key="`band-${index}`" class="gantt__band" :style="{ width: `${band.width}px` }">
                  {{ band.label }}
                </div>
              </div>
              <div class="gantt__ticks">
                <div
                  v-for="tick in ticks"
                  :key="tick.key"
                  class="gantt__tick"
                  :class="{ 'gantt__tick--weekend': tick.weekend, 'gantt__tick--month': tick.monthStart }"
                  :style="{ width: `${tick.width}px` }"
                >
                  {{ tick.label }}
                </div>
              </div>
            </div>
          </div>

          <div class="gantt__body">
            <div class="gantt__gridlines" aria-hidden="true">
              <div
                v-for="tick in ticks"
                :key="`cell-${tick.key}`"
                class="gantt__cell"
                :class="{ 'gantt__cell--weekend': tick.weekend, 'gantt__cell--month': tick.monthStart }"
                :style="{ width: `${tick.width}px` }"
              />
              <div v-if="todayX >= 0" class="gantt__today" :style="{ left: `${todayX}px` }">
                <span class="gantt__today-label">今天</span>
              </div>
            </div>
            <GanttRow
              v-for="row in rows"
              :key="row.key"
              v-memo="[row.key, row.item, row.collapsed, row.hasChildren, dayW, window.from, window.to]"
              class="gantt__row"
              :kind="row.kind"
              :item="row.item"
              :depth="row.depth"
              :collapsed="row.collapsed"
              :has-children="row.hasChildren"
              :day-w="dayW"
              :window="window"
              @toggle="emit('toggle', row.key)"
              @open="emit('open', $event)"
            />
          </div>
        </div>
      </div>
    </template>
  </section>
</template>

<style scoped>
.gantt {
  border: 1px solid var(--c-line);
  border-radius: var(--r-md);
  background: var(--c-surface);
  overflow: hidden;
}
.gantt__scroll {
  overflow: auto;
  height: clamp(360px, calc(100vh - 300px), 760px);
}
.gantt__grid {
  display: flex;
  flex-direction: column;
  min-width: 100%;
}
.gantt__head {
  position: sticky;
  top: 0;
  z-index: var(--z-thead);
  display: grid;
  height: 48px;
  background: var(--c-surface);
  border-bottom: 1px solid var(--c-line);
}
.gantt__corner {
  position: sticky;
  left: 0;
  z-index: 3;
  display: flex;
  align-items: center;
  padding: 0 var(--sp-3);
  background: var(--c-surface);
  border-right: 1px solid var(--c-line);
  font-size: var(--fs-micro);
  color: var(--c-ink-500);
}
.gantt__head-axis {
  display: flex;
  flex-direction: column;
}
.gantt__bands,
.gantt__ticks {
  display: flex;
  height: 24px;
  overflow: hidden;
}
.gantt__band {
  flex: none;
  display: flex;
  align-items: center;
  padding-left: var(--sp-2);
  border-left: 1px solid var(--c-line);
  font-size: var(--fs-micro);
  font-weight: 600;
  color: var(--c-ink-600);
  white-space: nowrap;
  overflow: hidden;
}
.gantt__tick {
  flex: none;
  display: flex;
  align-items: center;
  justify-content: center;
  border-left: 1px solid var(--c-line-soft);
  font-size: var(--fs-micro);
  color: var(--c-ink-500);
  font-variant-numeric: tabular-nums;
  white-space: nowrap;
  overflow: hidden;
}
.gantt__tick--weekend {
  background: var(--c-surface-hover);
}
.gantt__tick--month {
  border-left-color: var(--c-line);
}
.gantt__body {
  position: relative;
  display: flex;
  flex-direction: column;
}
.gantt__gridlines {
  position: absolute;
  inset: 0;
  display: flex;
  z-index: 0;
  pointer-events: none;
}
.gantt__cell {
  flex: none;
  height: 100%;
  border-left: 1px solid var(--c-line-soft);
}
.gantt__cell--weekend {
  background: var(--c-surface-hover);
}
.gantt__cell--month {
  border-left-color: var(--c-line);
}
.gantt__today {
  position: absolute;
  top: 0;
  bottom: 0;
  width: 0;
  border-left: 1.5px dashed var(--c-primary);
}
.gantt__today-label {
  position: absolute;
  top: 2px;
  left: 3px;
  padding: 1px 4px;
  border-radius: var(--r-sm);
  background: var(--c-primary);
  color: #fff;
  font-size: 10px;
  line-height: 14px;
  white-space: nowrap;
}
.gantt__row {
  position: relative;
  z-index: 1;
}
.gantt__notice {
  margin: 0;
  padding: var(--sp-2) var(--sp-3);
  border-bottom: 1px solid var(--c-warn-border);
  background: var(--c-warn-soft);
  color: var(--c-ink-700);
  font-size: var(--fs-caption);
  line-height: 18px;
}
.gantt__empty {
  padding: var(--sp-8) var(--sp-4);
}
.gantt__error {
  padding: var(--sp-8) var(--sp-4);
}
.gantt__skeleton {
  padding: var(--sp-3);
}
.gantt__sk-head {
  height: 24px;
  margin-bottom: 8px;
  border-radius: var(--r-sm);
  background: var(--c-line-soft);
  animation: gantt-pulse 1.2s ease-in-out infinite;
}
.gantt__sk-row {
  margin-bottom: 1px;
  border-radius: var(--r-sm);
  background: var(--c-line-soft);
  animation: gantt-pulse 1.2s ease-in-out infinite;
}
@keyframes gantt-pulse {
  0%,
  100% {
    opacity: 0.55;
  }
  50% {
    opacity: 0.95;
  }
}
@media (prefers-reduced-motion: reduce) {
  .gantt__sk-head,
  .gantt__sk-row {
    animation: none;
    opacity: 0.7;
  }
}
</style>
