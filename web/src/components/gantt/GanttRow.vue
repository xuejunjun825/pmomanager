<script setup lang="ts">
/**
 * 甘特三态行（DESIGN §4.1）：主题 44 / 项目 32 / 任务 40，左列宽度取 LEFT_COLUMN（与表头同源）。
 * 折叠由 GanttChart 决定是否渲染子行，本组件只负责 `aria-expanded` 与图标旋转；
 * 任务条的起点/宽度来自 useGanttWindow，逾期与风险只读接口字段。
 */
import { computed } from 'vue'
import type { GanttProjectGroup, GanttTask, GanttThemeGroup } from '@/types/api'
import type { GanttWindow } from '@/composables/useGanttWindow'
import { LEFT_COLUMN, barGeometry, windowDays } from '@/composables/useGanttWindow'
import StatusBadge from '@/components/ui/StatusBadge.vue'
import GanttBar from '@/components/gantt/GanttBar.vue'

const props = defineProps<{
  kind: 'theme' | 'project' | 'task'
  item: GanttThemeGroup | GanttProjectGroup | GanttTask
  depth: number
  collapsed: boolean
  hasChildren: boolean
  dayW: number
  window: GanttWindow
}>()

const emit = defineEmits<{ (event: 'toggle'): void; (event: 'open', id: number): void }>()

const theme = computed(() => props.item as GanttThemeGroup)
const project = computed(() => props.item as GanttProjectGroup)
const task = computed(() => props.item as GanttTask)

const indent = computed(() => (props.kind === 'theme' ? 12 : props.kind === 'project' ? 24 : 36))
const railStyle = computed(() =>
  props.kind === 'theme'
    ? { paddingLeft: `${indent.value}px`, borderLeftColor: theme.value.unassigned ? 'var(--c-line)' : theme.value.color }
    : { paddingLeft: `${indent.value}px` },
)
const geometry = computed(() => barGeometry(task.value, props.window, props.dayW))
/** 行网格与表头 `.gantt__head` 同一来源：LEFT_COLUMN 调整时两处一起动，避免行与表头错位 */
const rowStyle = computed(() => ({ gridTemplateColumns: `${LEFT_COLUMN}px 1fr` }))
/** 时间轴总宽，与 GanttChart 的 totalWidth 同一算式：条与 tooltip 都以它为界 */
const laneWidth = computed(() => windowDays(props.window) * props.dayW)
const ownerName = computed(() => task.value.owner?.name ?? '未指派')
</script>

<template>
  <div class="row" :class="[`row--${kind}`, { 'row--collapsed': collapsed }]" :style="rowStyle">
    <div class="row__rail" :style="railStyle">
      <button
        v-if="kind !== 'task'"
        class="row__toggle"
        type="button"
        :aria-expanded="!collapsed"
        :aria-label="`${collapsed ? '展开' : '折叠'}${kind === 'theme' ? theme.themeName : project.projectName}`"
        :disabled="!hasChildren"
        @click.stop="emit('toggle')"
      >
        <svg width="16" height="16" viewBox="0 0 16 16" aria-hidden="true">
          <path d="M4.5 6.5 8 10l3.5-3.5" fill="none" stroke="currentColor" stroke-width="1.5" stroke-linecap="round" stroke-linejoin="round" />
        </svg>
      </button>

      <template v-if="kind === 'theme'">
        <span
          class="row__dot"
          :class="{ 'row__dot--empty': theme.unassigned }"
          :style="theme.unassigned ? undefined : { background: theme.color }"
          aria-hidden="true"
        />
        <span class="row__name row__name--theme">{{ theme.themeName }}</span>
        <span v-if="!theme.enabled && !theme.unassigned" class="row__off">（已停用）</span>
        <span class="row__counts">
          {{ theme.taskCount }} 条<template v-if="theme.overdueCount"> · 逾期 {{ theme.overdueCount }}</template
          ><template v-if="theme.riskCount"> · 风险 {{ theme.riskCount }}</template>
        </span>
      </template>

      <template v-else-if="kind === 'project'">
        <span class="row__name row__name--project">{{ project.projectName }}</span>
        <span class="row__counts">{{ project.taskCount }}</span>
      </template>

      <template v-else>
        <StatusBadge :status="task.status" size="sm" />
        <span class="row__task-text">
          <span class="row__name row__name--task" :title="task.title">
            {{ task.title }}<span v-if="task.estimatedStart" class="row__approx" title="起点按创建日推算">≈</span>
          </span>
          <span class="row__sub">{{ ownerName }} · {{ task.sourceLabel || '无来源' }}</span>
        </span>
      </template>
    </div>

    <div class="row__lane">
      <GanttBar
        v-if="kind === 'task'"
        :task="task"
        :x="geometry.x"
        :width="geometry.width"
        :pinned-start="task.pinnedStart"
        :pinned-end="task.pinnedEnd"
        :lane-width="laneWidth"
        @open="emit('open', $event)"
      />
    </div>
  </div>
</template>

<style scoped>
.row {
  display: grid;
  /* 列宽由 rowStyle 从 LEFT_COLUMN 注入，与 .gantt__head 同源，禁止再写字面量 280 */
  position: relative;
}
.row--theme {
  height: 44px;
}
.row--project {
  height: 32px;
}
.row--task {
  height: 40px;
}
.row__rail {
  position: sticky;
  left: 0;
  z-index: 2;
  display: flex;
  align-items: center;
  gap: var(--sp-2);
  min-width: 0;
  padding-right: var(--sp-3);
  background: var(--c-surface);
  border-bottom: 1px solid var(--c-line-soft);
}
.row__rail::before {
  content: '';
  position: absolute;
  left: 0;
  top: 0;
  bottom: 0;
  width: 2px;
  background: transparent;
}
.row__lane {
  position: relative;
  border-bottom: 1px solid var(--c-line-soft);
  background: transparent;
}
.row--theme .row__rail {
  border-left: 3px solid var(--c-line);
}
.row__toggle {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  flex: none;
  width: 20px;
  height: 20px;
  padding: 0;
  border: 0;
  border-radius: var(--r-sm);
  background: transparent;
  color: var(--c-ink-500);
  cursor: pointer;
}
.row__toggle:hover:not(:disabled) {
  background: var(--c-surface-hover);
  color: var(--c-ink-700);
}
.row__toggle:focus-visible {
  outline: none;
  box-shadow: var(--focus-ring);
}
.row__toggle svg {
  transition: transform var(--t-toggle) var(--ease);
}
.row--collapsed .row__toggle svg {
  transform: rotate(-90deg);
}
.row__toggle:disabled {
  cursor: default;
}
.row__dot {
  flex: none;
  width: 8px;
  height: 8px;
  border-radius: 50%;
}
.row__dot--empty {
  border: 1px solid var(--c-ink-400);
  background: transparent;
}
.row__name {
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  color: var(--c-ink-900);
}
.row__name--theme {
  font-size: 14px;
  font-weight: 600;
}
.row__name--project {
  font-size: var(--fs-body-sm);
  font-weight: 500;
}
.row__name--task {
  font-size: var(--fs-body-sm);
  font-weight: 500;
}
.row__off {
  flex: none;
  font-size: var(--fs-micro);
  color: var(--c-ink-400);
}
.row__counts {
  flex: none;
  margin-left: auto;
  font-size: var(--fs-micro);
  color: var(--c-ink-500);
  font-variant-numeric: tabular-nums;
  white-space: nowrap;
}
.row__task-text {
  display: flex;
  flex-direction: column;
  gap: 1px;
  min-width: 0;
}
.row__sub {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  font-size: var(--fs-micro);
  line-height: 14px;
  color: var(--c-ink-500);
}
.row__approx {
  margin-left: 2px;
  color: var(--c-ink-400);
}
.row--task:hover .row__rail {
  background: var(--c-surface-hover);
}
.row--task:focus-within .row__rail::before {
  background: var(--c-primary);
}
</style>
