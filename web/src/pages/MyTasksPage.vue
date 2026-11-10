<script setup lang="ts">
/**
 * 我的任务（DESIGN §4.4 / AC-10）：负责人视角的待办清单。
 * 一次拉本人任务、客户端分组，保证「今天到期 / 逾期 / 待验收」三个数与列表出自同一份数据。
 * 逾期只读 isOverdue / overdueDays（AC-11）。
 */
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import PageHeader from '@/components/ui/PageHeader.vue'
import AppButton from '@/components/ui/AppButton.vue'
import AppTextarea from '@/components/ui/AppTextarea.vue'
import Modal from '@/components/ui/Modal.vue'
import StatusBadge from '@/components/ui/StatusBadge.vue'
import OverduePill from '@/components/ui/OverduePill.vue'
import SourceTag from '@/components/ui/SourceTag.vue'
import EmptyState from '@/components/ui/EmptyState.vue'
import ErrorState from '@/components/ui/ErrorState.vue'
import { taskApi } from '@/api/tasks'
import { toApiError, type ApiError } from '@/api/http'
import { useAuthStore } from '@/stores/auth'
import { useTaskActions } from '@/composables/useTaskActions'
import { formatDate } from '@/utils/format'
import type { TaskRow } from '@/types/api'

type TabKey = 'todo' | 'accept' | 'done'

interface Tab {
  key: TabKey
  label: string
  emptyTitle: string
  emptyHint: string
}

const TABS: Tab[] = [
  { key: 'todo', label: '待办', emptyTitle: '今天没有待办任务', emptyHint: '截止日期临近的任务会自动出现在这里' },
  { key: 'accept', label: '待验收', emptyTitle: '暂无待验收任务', emptyHint: '提交验收后，等派发人确认' },
  { key: 'done', label: '已完成', emptyTitle: '还没有完成的任务', emptyHint: '验收通过或取消的任务会归档在这里' },
]

const router = useRouter()
const auth = useAuthStore()

const tasks = ref<TaskRow[]>([])
const loading = ref(true)
const error = ref<ApiError | null>(null)
const tab = ref<TabKey>('todo')

const { run, busy } = useTaskActions({
  onUpdated: () => void load(),
  onConflict: () => void load(),
})

/** 与后端同一个时区口径（Asia/Shanghai），避免浏览器时区不同导致「今天到期」对不上 */
const TZ = 'Asia/Shanghai'
const now = new Date()
const todayIso = new Intl.DateTimeFormat('en-CA', { timeZone: TZ }).format(now)
const weekday = new Intl.DateTimeFormat('zh-CN', { timeZone: TZ, weekday: 'short' }).format(now)
const todayLabel = `今天 ${todayIso.slice(5)} ${weekday}`

const todoTasks = computed(() =>
  tasks.value.filter((task) => task.status === 'PENDING' || task.status === 'IN_PROGRESS'),
)
const acceptTasks = computed(() => tasks.value.filter((task) => task.status === 'PENDING_ACCEPT'))
const doneTasks = computed(() => tasks.value.filter((task) => task.status === 'DONE' || task.status === 'CANCELLED'))

const stats = computed(() => ({
  dueToday: todoTasks.value.filter((task) => task.dueDate === todayIso).length,
  overdue: tasks.value.filter((task) => task.isOverdue).length,
  pendingAccept: acceptTasks.value.length,
}))

const counts = computed<Record<TabKey, number>>(() => ({
  todo: todoTasks.value.length,
  accept: acceptTasks.value.length,
  done: doneTasks.value.length,
}))

const currentTab = computed(() => TABS.find((item) => item.key === tab.value) ?? TABS[0])

const visibleTasks = computed(() => {
  if (tab.value === 'accept') return acceptTasks.value
  if (tab.value === 'done') return doneTasks.value
  return todoTasks.value
})

function monthDay(value?: string): string {
  if (!value) return '—'
  return formatDate(value).slice(5)
}

function primaryAction(task: TaskRow): { key: 'start' | 'submit'; label: string } | null {
  if (task.status === 'PENDING') return { key: 'start', label: '开始' }
  if (task.status === 'IN_PROGRESS') return { key: 'submit', label: '提交验收' }
  return null
}

/* ------------------------------------------------------------ 提交验收 */

const submitting = ref<TaskRow | null>(null)
const note = ref('')
const link = ref('')
const submitError = ref('')

function openSubmit(task: TaskRow): void {
  submitting.value = task
  note.value = ''
  link.value = ''
  submitError.value = ''
}

function closeSubmit(): void {
  if (busy.value === 'submit') return
  submitting.value = null
}

async function doSubmit(): Promise<void> {
  const task = submitting.value
  if (!task) return
  const trimmed = link.value.trim()
  if (trimmed && !/^https?:\/\/\S+$/i.test(trimmed)) {
    submitError.value = '交付链接需以 http:// 或 https:// 开头'
    return
  }
  submitError.value = ''
  const detail = await run(task.id, 'submit', {
    command: { completionNote: note.value.trim() || undefined, completionUrl: trimmed || undefined },
  })
  if (detail) submitting.value = null
}

async function start(task: TaskRow): Promise<void> {
  await run(task.id, 'start')
}

function openTask(task: TaskRow): void {
  void router.push(`/tasks/${task.id}`)
}

async function load(): Promise<void> {
  loading.value = true
  error.value = null
  try {
    const page = await taskApi.list({
      ownerId: auth.user?.id,
      page: 1,
      size: 100,
      sort: 'due_asc',
    })
    tasks.value = Array.isArray(page?.items) ? page.items : []
  } catch (caught) {
    error.value = toApiError(caught)
    tasks.value = []
  } finally {
    loading.value = false
  }
}

onMounted(() => {
  void load()
})
</script>

<template>
  <section class="my">
    <PageHeader title="我的任务" description="只列你负责的任务；逾期与待验收实时按服务端计算">
      <template #meta>
        <span class="my__today">{{ todayLabel }}</span>
      </template>
    </PageHeader>

    <div class="stats">
      <div class="stats__item">
        <span class="stats__label">今天到期</span>
        <span class="stats__value num">{{ stats.dueToday }}</span>
      </div>
      <div class="stats__item" :class="{ 'is-danger': stats.overdue > 0 }">
        <span class="stats__label">逾期</span>
        <span class="stats__value num">{{ stats.overdue }}</span>
      </div>
      <div class="stats__item">
        <span class="stats__label">待验收</span>
        <span class="stats__value num">{{ stats.pendingAccept }}</span>
      </div>
    </div>

    <div class="tabs" role="tablist" aria-label="我的任务分组">
      <button
        v-for="item in TABS"
        :key="item.key"
        type="button"
        role="tab"
        class="tabs__item"
        :class="{ 'is-active': tab === item.key }"
        :aria-selected="tab === item.key"
        @click="tab = item.key"
      >
        {{ item.label }}
        <span class="tabs__count num">{{ counts[item.key] }}</span>
      </button>
    </div>

    <ErrorState
      v-if="error"
      title="我的任务加载失败"
      :description="error.message || '请求没有完成，请稍后重试'"
      :trace-id="error.traceId"
      @retry="load"
    />

    <div v-else-if="loading" class="cards" aria-busy="true">
      <div v-for="index in 3" :key="index" class="card card--skeleton">
        <span class="sk-block" style="width: 40%; height: 18px" />
        <span class="sk-block" style="width: 80%; height: 20px" />
        <span class="sk-block" style="width: 55%; height: 14px" />
      </div>
    </div>

    <EmptyState
      v-else-if="!visibleTasks.length"
      :title="currentTab.emptyTitle"
      :description="currentTab.emptyHint"
    />

    <div v-else class="cards">
      <article
        v-for="task in visibleTasks"
        :key="task.id"
        class="card"
        :class="{ 'is-overdue': task.isOverdue }"
      >
        <header class="card__head">
          <StatusBadge :status="task.status" size="sm" />
          <OverduePill v-if="task.isOverdue" :days="task.overdueDays" />
          <OverduePill v-else-if="task.dueSoon" variant="due-soon" />
        </header>

        <h3 class="card__title" :title="task.title">{{ task.title }}</h3>

        <p class="card__meta">
          <SourceTag
            v-if="task.source"
            :code="task.source.type"
            :name="task.source.typeLabel"
            :color="task.source.color"
            :soft-color="task.source.softColor"
            :border-color="task.source.borderColor"
            :meeting-title="task.source.meetingTitle"
          />
          <span class="card__due">截止 {{ monthDay(task.dueDate) }}</span>
        </p>

        <footer class="card__foot">
          <AppButton variant="ghost" size="compact" @click="openTask(task)">查看</AppButton>
          <AppButton
            v-if="primaryAction(task)?.key === 'start'"
            variant="primary"
            size="compact"
            :loading="busy === 'start'"
            @click="start(task)"
          >
            开始
          </AppButton>
          <AppButton
            v-else-if="primaryAction(task)?.key === 'submit'"
            variant="primary"
            size="compact"
            @click="openSubmit(task)"
          >
            提交验收
          </AppButton>
          <span v-else-if="task.status === 'PENDING_ACCEPT'" class="card__hint">等待派发人确认</span>
        </footer>
      </article>
    </div>

    <Modal
      :open="submitting !== null"
      title="提交验收"
      :description="submitting ? `任务：${submitting.title}` : undefined"
      size="md"
      @close="closeSubmit"
    >
      <div class="submit-form">
        <AppTextarea
          v-model="note"
          label="完成说明"
          :rows="4"
          :maxlength="500"
          placeholder="做到了什么、还差什么（选填）"
        />
        <AppTextarea
          v-model="link"
          label="交付链接"
          :rows="2"
          :maxlength="500"
          placeholder="https://…（选填）"
          :error="submitError"
        />
      </div>
      <template #footer>
        <AppButton variant="secondary" :disabled="busy === 'submit'" @click="closeSubmit">取消</AppButton>
        <AppButton variant="primary" :loading="busy === 'submit'" loading-text="提交中" @click="doSubmit">
          提交验收
        </AppButton>
      </template>
    </Modal>
  </section>
</template>

<style scoped>
.my {
  display: flex;
  flex-direction: column;
  gap: var(--sp-4);
}
.my__today {
  font-size: var(--fs-body-sm);
  color: var(--c-ink-500);
}
.stats {
  display: flex;
  gap: var(--sp-8);
  padding: var(--sp-4) var(--sp-5);
  border: 1px solid var(--c-line);
  border-radius: var(--r-lg);
  background: var(--c-surface);
}
.stats__item {
  display: flex;
  flex-direction: column;
  gap: var(--sp-1);
  min-width: 88px;
}
.stats__label {
  font-size: var(--fs-caption);
  color: var(--c-ink-500);
}
.stats__value {
  font-size: var(--fs-h1);
  font-weight: 600;
  line-height: 1.1;
  color: var(--c-ink-900);
}
.stats__item.is-danger .stats__value {
  color: var(--c-danger-text);
}
.tabs {
  display: flex;
  gap: var(--sp-1);
  border-bottom: 1px solid var(--c-line);
}
.tabs__item {
  display: inline-flex;
  align-items: center;
  gap: var(--sp-2);
  height: 40px;
  padding: 0 var(--sp-3);
  border: 0;
  border-bottom: 2px solid transparent;
  background: transparent;
  color: var(--c-ink-600);
  font-size: var(--fs-body-sm);
  cursor: pointer;
  transition: color var(--t-toggle) var(--ease), border-color var(--t-toggle) var(--ease);
}
.tabs__item:hover {
  color: var(--c-ink-900);
}
.tabs__item.is-active {
  color: var(--c-primary);
  border-bottom-color: var(--c-primary);
  font-weight: 600;
}
.tabs__count {
  min-width: 20px;
  padding: 0 6px;
  border-radius: 999px;
  background: var(--c-surface-hover);
  color: var(--c-ink-600);
  font-size: var(--fs-micro);
  line-height: 18px;
  text-align: center;
}
.cards {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(320px, 1fr));
  gap: var(--sp-4);
}
@media (min-width: 1440px) {
  .cards {
    grid-template-columns: repeat(3, minmax(0, 1fr));
  }
}
@media (min-width: 1024px) and (max-width: 1439px) {
  .cards {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
}
@media (max-width: 1023px) {
  .cards {
    grid-template-columns: minmax(0, 1fr);
  }
}
.card {
  position: relative;
  display: flex;
  flex-direction: column;
  gap: var(--sp-3);
  padding: var(--sp-4);
  border: 1px solid var(--c-line);
  border-radius: var(--r-lg);
  background: var(--c-surface);
  transition: border-color var(--t-hover) var(--ease), box-shadow var(--t-hover) var(--ease);
}
.card:hover {
  border-color: var(--c-line);
  box-shadow: var(--sh-1);
}
.card.is-overdue {
  box-shadow: inset 3px 0 0 var(--c-danger);
}
.card--skeleton {
  gap: var(--sp-3);
}
.card__head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--sp-2);
  min-height: 22px;
}
.card__title {
  margin: 0;
  font-size: 15px;
  font-weight: 600;
  line-height: 1.4;
  color: var(--c-ink-900);
  display: -webkit-box;
  -webkit-line-clamp: 2;
  -webkit-box-orient: vertical;
  overflow: hidden;
}
.card__meta {
  display: flex;
  align-items: center;
  gap: var(--sp-2);
  margin: 0;
  font-size: var(--fs-caption);
  color: var(--c-ink-500);
}
.card__due {
  white-space: nowrap;
}
.card__foot {
  display: flex;
  align-items: center;
  justify-content: flex-end;
  gap: var(--sp-2);
  margin-top: auto;
}
.card__hint {
  font-size: var(--fs-caption);
  color: var(--c-ink-500);
}
.submit-form {
  display: flex;
  flex-direction: column;
  gap: var(--sp-4);
}
</style>
