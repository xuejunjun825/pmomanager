<script setup lang="ts">
/**
 * 会议任务进度区（r7 / AC-4 / DESIGN §3.5）。
 *
 * 单一计数口径：四个数字全部由 `items` 这一份数组算出，`computed` 只算一次；
 * 模板里禁止 `filter().length`，页面也不再把会议行上的 taskCount/unfinishedCount 显示一遍——
 * 否则同一个「未完成」会出现两个不同的数字。
 *
 * 口径与后端 TaskSql 对齐：
 * - 已转 = 本场会议已生成任务数（分母 = 决议总数）
 * - 未完成 = status NOT IN (DONE, CANCELLED)（与 TaskSql.NOT_FINISHED 一致）
 * - 逾期 = 服务端算好的 isOverdue（前端不推算日期）
 * - 已完成 = status === 'DONE'（CANCELLED 计为已结束但不计完成）
 */
import { computed } from 'vue'
import EmptyState from '@/components/ui/EmptyState.vue'
import ErrorState from '@/components/ui/ErrorState.vue'
import OverduePill from '@/components/ui/OverduePill.vue'
import StatusBadge from '@/components/ui/StatusBadge.vue'
import ThemeTag from '@/components/ui/ThemeTag.vue'
import { formatDate } from '@/utils/format'
import type { ApiError } from '@/api/http'
import type { TaskRow } from '@/types/api'

const props = withDefaults(
  defineProps<{
    items: TaskRow[]
    /** 本场会议的决议总数（已转任务的分母） */
    decisionTotal: number
    loading?: boolean
    error?: ApiError | null
  }>(),
  { loading: false, error: null },
)

const emit = defineEmits<{ retry: []; open: [id: number] }>()

const counts = computed(() => {
  const list = props.items ?? []
  let unfinished = 0
  let overdue = 0
  let done = 0
  for (const task of list) {
    if (task.status !== 'DONE' && task.status !== 'CANCELLED') unfinished += 1
    if (task.status === 'DONE') done += 1
    if (task.isOverdue) overdue += 1
  }
  return {
    converted: list.length,
    total: Math.max(props.decisionTotal ?? 0, list.length),
    unfinished,
    overdue,
    done,
  }
})

/** 状态文案：先看有没有转、再看有没有未完成，最后才是闭环 */
const stateText = computed(() => {
  const { converted, unfinished } = counts.value
  if (converted === 0) return { text: '待派发', tone: 'neutral' as const }
  if (unfinished === 0) return { text: '已闭环', tone: 'success' as const }
  return { text: `未完成 ${unfinished} 条`, tone: 'warning' as const }
})

const isEmpty = computed(() => !props.loading && !props.error && (props.items ?? []).length === 0)

/** 风险列：逾期必红；「压验收」只在待验收上出现，表示卡在验收环节 */
function isStuck(task: TaskRow): boolean {
  return task.status === 'PENDING_ACCEPT' && (task.isOverdue || task.atRisk === true)
}
</script>

<template>
  <section class="progress" aria-label="会议任务进度">
    <header class="progress__head">
      <div>
        <h2 class="progress__title">任务进度</h2>
        <p class="progress__hint">本场会议转出的任务，按截止日期排序；点击行进入任务详情。</p>
      </div>
      <span class="progress__state" :class="`progress__state--${stateText.tone}`">{{ stateText.text }}</span>
    </header>

    <div class="progress__cards">
      <div class="stat">
        <span class="stat__label">已转任务</span>
        <span class="stat__value num">{{ counts.converted }}<span class="stat__den">/{{ counts.total }}</span></span>
      </div>
      <div class="stat">
        <span class="stat__label">未完成</span>
        <span class="stat__value num" :class="{ 'stat__value--muted': counts.unfinished === 0 }">{{ counts.unfinished }}</span>
      </div>
      <div class="stat" :class="{ 'stat--danger': counts.overdue > 0 }">
        <span class="stat__label">逾期</span>
        <span class="stat__value num" :class="{ 'stat__value--danger': counts.overdue > 0 }">{{ counts.overdue }}</span>
      </div>
      <div class="stat">
        <span class="stat__label">已完成</span>
        <span class="stat__value num" :class="{ 'stat__value--muted': counts.done === 0 }">{{ counts.done }}</span>
      </div>
    </div>

    <ErrorState
      v-if="error"
      title="任务进度加载失败"
      :description="error.message"
      :trace-id="error.traceId ?? undefined"
      @retry="emit('retry')"
    />

    <EmptyState
      v-else-if="isEmpty"
      title="暂无已转任务"
      description="勾选上方决议项一键派发，转出的任务会出现在这里。"
      size="sm"
    />

    <div v-else class="table" :class="{ 'table--loading': loading }">
      <div class="table__head" role="row">
        <span class="col col--title">任务标题</span>
        <span class="col col--owner">负责人</span>
        <span class="col col--theme">主题</span>
        <span class="col col--status">状态</span>
        <span class="col col--due">截止</span>
        <span class="col col--risk">风险</span>
      </div>
      <button
        v-for="task in items"
        :key="task.id"
        type="button"
        class="table__row"
        :data-task="task.id"
        @click="emit('open', task.id)"
      >
        <span class="col col--title ellipsis">{{ task.title }}</span>
        <span class="col col--owner ellipsis">{{ task.owner?.name ?? '—' }}</span>
        <span class="col col--theme">
          <ThemeTag
            v-if="task.theme"
            :name="task.theme.name"
            :color="task.theme.color"
            :soft-color="task.theme.softColor"
            :border-color="task.theme.borderColor"
            :enabled="task.theme.enabled"
          />
          <span v-else class="muted">—</span>
        </span>
        <span class="col col--status"><StatusBadge :status="task.status" size="sm" /></span>
        <span class="col col--due num">{{ formatDate(task.dueDate) }}</span>
        <span class="col col--risk">
          <OverduePill v-if="task.isOverdue" :days="task.overdueDays" />
          <span v-if="isStuck(task)" class="stuck" :title="`待验收 ${task.overdueDays || ''} 天未闭环`">压验收</span>
          <span v-if="!task.isOverdue && !isStuck(task)" class="muted">—</span>
        </span>
      </button>
    </div>
  </section>
</template>

<style scoped>
.progress {
  display: flex;
  flex-direction: column;
  gap: var(--sp-4);
  padding: var(--sp-5);
  border: 1px solid var(--c-line);
  border-radius: var(--r-lg);
  background: var(--c-surface);
}
.progress__head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: var(--sp-4);
}
.progress__title {
  margin: 0;
  font-size: var(--fs-h3);
  font-weight: 600;
  color: var(--c-ink-900);
}
.progress__hint {
  margin: var(--sp-1) 0 0;
  font-size: var(--fs-caption);
  color: var(--c-ink-500);
}
.progress__state {
  flex: none;
  padding: 2px 10px;
  border-radius: var(--r-pill);
  font-size: var(--fs-caption);
  font-weight: 500;
  border: 1px solid var(--c-line);
  color: var(--c-ink-600);
  background: var(--c-bg);
}
.progress__state--success {
  color: var(--c-success);
  border-color: var(--c-success-border, #abefc6);
  background: var(--c-success-soft, #ecfdf3);
}
.progress__state--warning {
  color: var(--c-warn);
  border-color: var(--c-warn-border, #fedf89);
  background: var(--c-warn-soft, #fffaeb);
}
.progress__cards {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: var(--sp-3);
}
.stat {
  position: relative;
  display: flex;
  flex-direction: column;
  gap: var(--sp-1);
  padding: var(--sp-3) var(--sp-4);
  border: 1px solid var(--c-line);
  border-radius: var(--r-md);
  background: var(--c-bg);
}
.stat--danger {
  box-shadow: inset 3px 0 0 var(--c-danger);
}
.stat__label {
  font-size: 11px;
  line-height: 16px;
  color: var(--c-ink-500);
}
.stat__value {
  font-size: 20px;
  line-height: 28px;
  font-weight: 600;
  color: var(--c-ink-900);
}
.stat__den {
  font-size: 13px;
  font-weight: 500;
  color: var(--c-ink-400);
}
.stat__value--muted {
  color: var(--c-ink-400);
}
.stat__value--danger {
  color: var(--c-danger-text);
}
.table {
  border-top: 1px solid var(--c-line);
}
.table--loading {
  opacity: 0.6;
  pointer-events: none;
}
.table__head,
.table__row {
  display: grid;
  grid-template-columns: minmax(0, 1fr) 96px 120px 88px 96px 96px;
  align-items: center;
  gap: var(--sp-3);
  width: 100%;
  padding: 0 var(--sp-2);
  text-align: left;
}
.table__head {
  height: 36px;
  font-size: var(--fs-caption);
  color: var(--c-ink-500);
}
.table__row {
  height: 48px;
  border: 0;
  border-top: 1px solid var(--c-line);
  background: transparent;
  font-size: var(--fs-body-sm);
  color: var(--c-ink-700);
  cursor: pointer;
}
.table__row:hover {
  background: var(--c-bg);
}
.table__row:focus-visible {
  outline: 2px solid var(--c-primary);
  outline-offset: -2px;
}
.col {
  min-width: 0;
}
.ellipsis {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.muted {
  color: var(--c-ink-400);
}
.stuck {
  margin-left: var(--sp-2);
  font-size: 11px;
  line-height: 16px;
  color: var(--c-warn);
}
@media (max-width: 720px) {
  .progress__cards {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
  .table__head,
  .table__row {
    grid-template-columns: minmax(0, 1fr) 88px 84px 84px;
  }
  .col--theme,
  .col--status {
    display: none;
  }
}
</style>
