<script setup lang="ts">
/**
 * 任务详情（DESIGN §4.5）：左主区（状态步进 / 来源 / 交付 / 时间线）+ 右栏固定信息。
 * 三条纪律：
 * - 动作显隐只认服务端 permissions，前端角色判断只用于「编辑」这类入口；
 * - 409 之后立刻重取详情，界面不能停在旧状态；
 * - 逾期只展示 isOverdue / overdueDays，不做任何日期推算（AC-11）。
 */
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import OwnerChip from '@/components/ui/OwnerChip.vue'
import StatusBadge from '@/components/ui/StatusBadge.vue'
import OverduePill from '@/components/ui/OverduePill.vue'
import SourceTag from '@/components/ui/SourceTag.vue'
import ErrorState from '@/components/ui/ErrorState.vue'
import TaskActions from '@/components/task/TaskActions.vue'
import TransitionTimeline from '@/components/task/TransitionTimeline.vue'
import UrgeButton from '@/components/task/UrgeButton.vue'
import TaskFormDrawer from '@/components/task/TaskFormDrawer.vue'
import ProgressComposer from '@/components/task/ProgressComposer.vue'
import ProgressTimeline from '@/components/task/ProgressTimeline.vue'
import { taskApi } from '@/api/tasks'
import { metaApi } from '@/api/meta'
import { toApiError, type ApiError } from '@/api/http'
import { useAuthStore } from '@/stores/auth'
import { formatDate, formatDateTime, formatUrgeSummary } from '@/utils/format'
import { PRIORITY_LABELS, TASK_STATUS_LABELS } from '@/types/enums'
import type { TaskStatus } from '@/types/enums'
import type { MetaOptions, ProgressRow, TaskDetail, UrgeResult, UserOption } from '@/types/api'

const route = useRoute()
const router = useRouter()
const auth = useAuthStore()

const detail = ref<TaskDetail | null>(null)
const loading = ref(true)
const error = ref<ApiError | null>(null)
const meta = ref<MetaOptions | null>(null)
const editOpen = ref(false)

/* ---- 进度与 @（AC-7/8/10）：与详情并行加载，失败不阻塞详情 ---- */
const progress = ref<ProgressRow[]>([])
const progressTotal = ref(0)
const progressLoading = ref(true)
const progressError = ref<ApiError | null>(null)
const bannerDismissed = ref(false)
let anchorTimer: ReturnType<typeof setTimeout> | undefined

const mentionOnly = computed(() => detail.value?.permissions?.mentionOnly === true)
const mentionerName = computed(
  () => progress.value.find((row) => row.mentionedMe)?.author?.name || '同事',
)

const taskId = computed(() => Number(route.params.id))
const canEdit = computed(() => auth.role === 'ADMIN')
const owners = computed<UserOption[]>(() => (Array.isArray(meta.value?.owners) ? meta.value!.owners : []))
const meetings = computed(() => (Array.isArray(meta.value?.meetings) ? meta.value!.meetings : []))
const sourceTypes = computed(() => (Array.isArray(meta.value?.sourceTypes) ? meta.value!.sourceTypes : []))

/* ---- 通知深链 #progress-{id}：定位 + 1600ms 高亮 + 聚焦 ---- */
const anchorProgressId = computed(() => {
  const match = /^#progress-(\d+)$/.exec(route.hash || '')
  return match ? Number(match[1]) : 0
})

function locateAnchor(): void {
  const id = anchorProgressId.value
  if (!id) return
  const element = document.getElementById(`progress-${id}`)
  if (!element) return
  if (typeof element.scrollIntoView === 'function') element.scrollIntoView({ block: 'center' })
  element.classList.add('is-anchor')
  if (typeof (element as HTMLElement).focus === 'function') {
    ;(element as HTMLElement).focus({ preventScroll: true })
  }
  window.clearTimeout(anchorTimer)
  anchorTimer = window.setTimeout(() => element.classList.remove('is-anchor'), 1600)
}

function bannerKey(): string {
  return `mention-banner-${taskId.value}`
}

function readBannerDismissed(): boolean {
  try {
    return sessionStorage.getItem(bannerKey()) === '1'
  } catch {
    return false
  }
}

function dismissBanner(): void {
  bannerDismissed.value = true
  try {
    sessionStorage.setItem(bannerKey(), '1')
  } catch {
    // 隐私模式下 sessionStorage 不可用：本次会话内记住即可
  }
}

async function loadProgress(): Promise<void> {
  progressLoading.value = true
  progressError.value = null
  try {
    const data = await taskApi.progress(taskId.value, { limit: 50 })
    progress.value = Array.isArray(data?.items) ? data.items : []
    progressTotal.value = Number(data?.total ?? progress.value.length)
  } catch (caught) {
    progressError.value = toApiError(caught)
  } finally {
    progressLoading.value = false
  }
  await nextTick()
  locateAnchor()
}

/** 提交成功：服务端返回行直接前插，1 秒内出现在时间线（不整页重取） */
function onProgressCreated(row: ProgressRow): void {
  progress.value = [row, ...progress.value]
  progressTotal.value += 1
  void nextTick(() => locateAnchor())
}

const STEPS: TaskStatus[] = ['PENDING', 'IN_PROGRESS', 'PENDING_ACCEPT', 'DONE']

const stepIndex = computed(() => {
  if (!detail.value) return -1
  return STEPS.indexOf(detail.value.status)
})

const cancelled = computed(() => detail.value?.status === 'CANCELLED')

/** 名称来自服务端 join 的 source_types（AC-5），本地不再有第二套标签表 */
const sourceLabel = computed(() => {
  const source = detail.value?.source
  if (!source) return '—'
  return source.typeLabel ?? source.type
})

const urgeSummary = computed(() => {
  const task = detail.value
  if (!task) return '还没有催办'
  return formatUrgeSummary(task.urgeCount ?? 0, task.lastUrgedAt ?? null)
})

function stepState(index: number): 'done' | 'current' | 'future' {
  if (detail.value?.status === 'DONE') return 'done'
  if (stepIndex.value < 0) return index === 0 ? 'current' : 'future'
  if (index < stepIndex.value) return 'done'
  if (index === stepIndex.value) return 'current'
  return 'future'
}

async function load(): Promise<void> {
  if (!Number.isFinite(taskId.value) || taskId.value <= 0) {
    void router.replace('/404')
    return
  }
  loading.value = true
  error.value = null
  try {
    detail.value = await taskApi.get(taskId.value)
  } catch (caught) {
    const apiError = toApiError(caught)
    if (apiError.status === 404) {
      void router.replace('/404')
      return
    }
    if (apiError.status === 403) {
      void router.replace('/403')
      return
    }
    error.value = apiError
    detail.value = null
  } finally {
    loading.value = false
  }
}

async function loadMeta(): Promise<void> {
  try {
    meta.value = await metaApi.options()
  } catch {
    meta.value = null
  }
}

/** 409 之后重取：既刷新状态，也刷新 permissions，按钮不会停在可点状态 */
function onStale(): void {
  void load()
}

function onUpdated(next: TaskDetail): void {
  detail.value = next
}

function onUrged(result: UrgeResult): void {
  if (!detail.value) return
  detail.value = {
    ...detail.value,
    urgeCount: Number(result.urgeCount ?? detail.value.urgeCount ?? 0),
    lastUrgedAt: result.lastUrgedAt ?? detail.value.lastUrgedAt,
  }
}

function onSaved(next: TaskDetail): void {
  detail.value = next
  editOpen.value = false
}

watch(taskId, () => {
  bannerDismissed.value = readBannerDismissed()
  void load()
  void loadProgress()
})

watch([progress, () => route.hash], () => {
  void nextTick(() => locateAnchor())
})

onBeforeUnmount(() => window.clearTimeout(anchorTimer))

onMounted(() => {
  bannerDismissed.value = readBannerDismissed()
  void load()
  void loadProgress()
  void loadMeta()
})
</script>

<template>
  <section class="detail">
    <nav class="crumbs" aria-label="面包屑">
      <RouterLink to="/" class="crumbs__link">首页</RouterLink>
      <span class="crumbs__sep" aria-hidden="true">/</span>
      <RouterLink to="/tasks" class="crumbs__link">任务池</RouterLink>
      <span class="crumbs__sep" aria-hidden="true">/</span>
      <span class="crumbs__current">{{ detail?.title ?? '任务详情' }}</span>
    </nav>

    <div v-if="loading" class="detail__skeleton" aria-busy="true">
      <span class="sk-block" style="width: 46%; height: 28px" />
      <span class="sk-block" style="width: 100%; height: 120px" />
      <span class="sk-block" style="width: 100%; height: 200px" />
    </div>

    <ErrorState
      v-else-if="error"
      title="任务详情加载失败"
      :description="error.message || '请求没有完成，请稍后重试'"
      :trace-id="error.traceId"
      @retry="load"
    />

    <template v-else-if="detail">
      <!-- 被 @ 人视角（AC-11）：可读 + 可补进度，状态动作整组不渲染 -->
      <div v-if="mentionOnly && !bannerDismissed" class="mention-banner" role="status">
        <span class="mention-banner__icon" aria-hidden="true">@</span>
        <p class="mention-banner__text">
          你被 {{ mentionerName }} @ 过，可查看并补充进度；状态操作仅负责人与管理员可用。
        </p>
        <button type="button" class="mention-banner__close" aria-label="关闭提示" @click="dismissBanner">
          ×
        </button>
      </div>

      <header class="head">
        <div class="head__main">
          <h1 class="head__title">{{ detail.title }}</h1>
          <div class="head__badges">
            <StatusBadge :status="detail.status" />
            <OverduePill v-if="detail.isOverdue" :days="detail.overdueDays" />
            <OverduePill v-else-if="detail.dueSoon" variant="due-soon" />
          </div>
        </div>
        <div class="head__actions">
          <UrgeButton
            :task-id="detail.id"
            :owner-name="detail.owner?.name"
            :last-urged-at="detail.lastUrgedAt ?? null"
            :can-urge="Boolean(detail.permissions?.canUrge)"
            :disabled-reason="detail.permissions?.disabledReasons?.urge"
            :urge-count="detail.urgeCount"
            @urged="onUrged"
          />
          <TaskActions
            :task="detail"
            :owners="owners"
            :can-edit="canEdit"
            @updated="onUpdated"
            @stale="onStale"
            @edit="editOpen = true"
          />
        </div>
      </header>

      <div class="grid">
        <div class="main">
          <!-- ① 状态步进 -->
          <section class="panel">
            <h2 class="panel__title">进度</h2>
            <p v-if="cancelled" class="cancelled">
              任务已取消<span v-if="detail.completedAt"> · {{ formatDateTime(detail.completedAt) }}</span>
            </p>
            <ol v-else class="steps">
              <li
                v-for="(step, index) in STEPS"
                :key="step"
                class="steps__item"
                :class="`is-${stepState(index)}`"
              >
                <span class="steps__dot" aria-hidden="true">
                  <span v-if="stepState(index) === 'done'" class="steps__check">✓</span>
                </span>
                <span class="steps__label">{{ TASK_STATUS_LABELS[step] }}</span>
              </li>
            </ol>
          </section>

          <!-- ② 来源 -->
          <section class="panel">
            <h2 class="panel__title">来源</h2>
            <div class="source">
              <div class="source__head">
                <SourceTag
                  v-if="detail.source"
                  :code="detail.source.type"
                  :name="detail.source.typeLabel"
                  :color="detail.source.color"
                  :soft-color="detail.source.softColor"
                  :border-color="detail.source.borderColor"
                  :meeting-title="detail.source.meetingTitle"
                />
                <span class="source__type">{{ sourceLabel }}</span>
              </div>
              <template v-if="detail.source?.type === 'MEETING'">
                <p class="source__meeting">
                  <RouterLink
                    v-if="detail.source.meetingId"
                    :to="`/meetings/${detail.source.meetingId}`"
                    class="source__link"
                  >
                    {{ detail.source.meetingTitle || `会议 #${detail.source.meetingId}` }}
                  </RouterLink>
                  <span v-else>{{ detail.source.meetingTitle || '会议' }}</span>
                </p>
                <blockquote v-if="detail.source.note" class="source__note">
                  {{ detail.source.note }}
                </blockquote>
              </template>
              <p v-else class="source__note">{{ detail.source?.note || '没有填写来源说明' }}</p>
              <p class="source__tip">来源是创建时的快照，会议后续改动不会影响这条任务。</p>
            </div>
          </section>

          <!-- ③ 交付信息 -->
          <section class="panel">
            <h2 class="panel__title">交付信息</h2>
            <dl class="delivery">
              <div class="delivery__row">
                <dt>完成说明</dt>
                <dd>{{ detail.completionNote || '还没有提交验收' }}</dd>
              </div>
              <div class="delivery__row">
                <dt>交付链接</dt>
                <dd>
                  <a
                    v-if="detail.completionUrl"
                    :href="detail.completionUrl"
                    target="_blank"
                    rel="noopener noreferrer"
                    class="source__link"
                  >
                    {{ detail.completionUrl }}
                  </a>
                  <span v-else class="muted">—</span>
                </dd>
              </div>
              <div class="delivery__row">
                <dt>提交时间</dt>
                <dd>{{ detail.submittedAt ? formatDateTime(detail.submittedAt) : '—' }}</dd>
              </div>
            </dl>
          </section>

          <!-- ④ 事件时间线 -->
          <section class="panel">
            <h2 class="panel__title">流转记录</h2>
            <TransitionTimeline :events="detail.events ?? []" />
          </section>
        </div>

        <aside class="rail">
          <section class="panel panel--rail">
            <dl class="facts">
              <div class="facts__row">
                <dt>负责人</dt>
                <dd><OwnerChip :name="detail.owner?.name" size="sm" /></dd>
              </div>
              <div class="facts__row">
                <dt>截止日期</dt>
                <dd>
                  <span class="num">{{ formatDate(detail.dueDate) }}</span>
                  <span v-if="detail.isOverdue" class="facts__danger">
                    逾期 {{ detail.overdueDays }} 天
                  </span>
                </dd>
              </div>
              <div class="facts__row">
                <dt>派发人</dt>
                <dd>{{ detail.dispatcher?.name || '—' }}</dd>
              </div>
              <div class="facts__row">
                <dt>优先级</dt>
                <dd>{{ PRIORITY_LABELS[detail.priority] ?? '中' }}</dd>
              </div>
              <div class="facts__row">
                <dt>最近更新</dt>
                <dd>{{ formatDateTime(detail.updatedAt) }}</dd>
              </div>
              <div v-if="detail.completedAt" class="facts__row">
                <dt>完成时间</dt>
                <dd>{{ formatDateTime(detail.completedAt) }}</dd>
              </div>
              <div class="facts__row">
                <dt>催办次数</dt>
                <dd>{{ urgeSummary }}</dd>
              </div>
            </dl>
          </section>
          <p class="rail__note">逾期判定在每次读取时按 Asia/Shanghai 的今天重算，改期后立即生效。</p>
        </aside>
      </div>

      <TaskFormDrawer
        :open="editOpen"
        :task="detail"
        :owners="owners"
        :meetings="meetings"
        @close="editOpen = false"
        @saved="onSaved"
      />
    </template>
  </section>
</template>

<style scoped>
.detail {
  display: flex;
  flex-direction: column;
  gap: var(--sp-4);
}
.crumbs {
  display: flex;
  align-items: center;
  gap: var(--sp-2);
  font-size: var(--fs-caption);
  color: var(--c-ink-500);
}
.crumbs__link {
  color: var(--c-ink-500);
  text-decoration: none;
}
.crumbs__link:hover {
  color: var(--c-primary);
  text-decoration: underline;
}
.crumbs__sep {
  color: var(--c-ink-400);
}
.crumbs__current {
  color: var(--c-ink-700);
  max-width: 420px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.detail__skeleton {
  display: flex;
  flex-direction: column;
  gap: var(--sp-4);
}
.head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: var(--sp-4);
  flex-wrap: wrap;
}
.head__main {
  display: flex;
  flex-direction: column;
  gap: var(--sp-2);
  min-width: 0;
}
.head__title {
  margin: 0;
  font-size: var(--fs-h1);
  font-weight: 600;
  line-height: 1.3;
  color: var(--c-ink-900);
  overflow-wrap: anywhere;
}
.head__badges {
  display: flex;
  align-items: center;
  gap: var(--sp-2);
}
.head__actions {
  display: flex;
  align-items: center;
  gap: var(--sp-2);
  flex-wrap: wrap;
  justify-content: flex-end;
}
.grid {
  display: grid;
  grid-template-columns: minmax(0, 664px) 320px;
  gap: var(--sp-6);
  align-items: start;
}
@media (max-width: 1080px) {
  .grid {
    grid-template-columns: minmax(0, 1fr);
  }
}
.main {
  display: flex;
  flex-direction: column;
  gap: var(--sp-4);
  min-width: 0;
}
.panel {
  padding: var(--sp-4);
  border: 1px solid var(--c-line);
  border-radius: var(--r-lg);
  background: var(--c-surface);
}
.panel--rail {
  padding: var(--sp-4);
}
.panel__title {
  margin: 0 0 var(--sp-3);
  font-size: var(--fs-caption);
  font-weight: 600;
  letter-spacing: 0.04em;
  text-transform: uppercase;
  color: var(--c-ink-500);
}
.steps {
  display: flex;
  align-items: center;
  gap: var(--sp-6);
  margin: 0;
  padding: 0;
  list-style: none;
}
.steps__item {
  display: flex;
  align-items: center;
  gap: var(--sp-2);
  font-size: var(--fs-body-sm);
  color: var(--c-ink-400);
}
.steps__dot {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 12px;
  height: 12px;
  border-radius: 50%;
  border: 1px solid var(--c-line);
  background: var(--c-surface);
  font-size: 8px;
  line-height: 1;
}
.steps__item.is-current {
  color: var(--c-primary);
  font-weight: 600;
}
.steps__item.is-current .steps__dot {
  border-color: var(--c-primary);
  background: var(--c-primary);
  box-shadow: 0 0 0 3px var(--c-primary-soft);
}
.steps__item.is-done {
  color: var(--c-ink-700);
}
.steps__item.is-done .steps__dot {
  border-color: var(--c-success);
  background: var(--c-success);
  color: #fff;
}
.steps__check {
  color: #fff;
  font-size: 8px;
}
.cancelled {
  margin: 0;
  font-size: var(--fs-body-sm);
  color: var(--c-ink-600);
}
.source {
  display: flex;
  flex-direction: column;
  gap: var(--sp-2);
}
.source__head {
  display: flex;
  align-items: center;
  gap: var(--sp-2);
}
.source__type {
  font-size: var(--fs-caption);
  color: var(--c-ink-500);
}
.source__meeting {
  margin: 0;
  font-size: var(--fs-body-sm);
  font-weight: 600;
}
.source__link {
  color: var(--c-primary);
  text-decoration: none;
  overflow-wrap: anywhere;
}
.source__link:hover {
  text-decoration: underline;
}
.source__note {
  margin: 0;
  padding: var(--sp-2) var(--sp-3);
  border-left: 2px solid var(--c-line);
  background: var(--c-surface-hover);
  border-radius: 0 var(--r-sm) var(--r-sm) 0;
  font-size: var(--fs-body-sm);
  color: var(--c-ink-700);
  white-space: pre-wrap;
}
.source__tip {
  margin: 0;
  font-size: var(--fs-caption);
  color: var(--c-ink-400);
}
.delivery {
  display: flex;
  flex-direction: column;
  gap: var(--sp-3);
  margin: 0;
}
.delivery__row {
  display: grid;
  grid-template-columns: 88px minmax(0, 1fr);
  gap: var(--sp-3);
  font-size: var(--fs-body-sm);
}
.delivery__row dt {
  color: var(--c-ink-500);
}
.delivery__row dd {
  margin: 0;
  color: var(--c-ink-900);
  overflow-wrap: anywhere;
  white-space: pre-wrap;
}
.muted {
  color: var(--c-ink-400);
}
.facts {
  display: flex;
  flex-direction: column;
  gap: var(--sp-3);
  margin: 0;
}
.facts__row {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  gap: var(--sp-3);
  font-size: var(--fs-body-sm);
}
.facts__row dt {
  color: var(--c-ink-500);
  white-space: nowrap;
}
.facts__row dd {
  margin: 0;
  color: var(--c-ink-900);
  text-align: right;
}
.facts__danger {
  display: block;
  color: var(--c-danger-text);
  font-size: var(--fs-caption);
  font-weight: 600;
}
.rail {
  display: flex;
  flex-direction: column;
  gap: var(--sp-3);
  position: sticky;
  top: calc(var(--topbar-h) + var(--sp-4));
}
.rail__note {
  margin: 0;
  font-size: var(--fs-caption);
  line-height: 1.5;
  color: var(--c-ink-400);
}
</style>
