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
import ProjectTag from '@/components/ui/ProjectTag.vue'
import ThemeTag from '@/components/ui/ThemeTag.vue'
import ErrorState from '@/components/ui/ErrorState.vue'
import TaskActions from '@/components/task/TaskActions.vue'
import TaskStatusFlow from '@/components/task/TaskStatusFlow.vue'
import UrgeButton from '@/components/task/UrgeButton.vue'
import TaskFormDrawer from '@/components/task/TaskFormDrawer.vue'
import ProgressComposer from '@/components/task/ProgressComposer.vue'
import ProgressTimeline from '@/components/task/ProgressTimeline.vue'
import { taskApi } from '@/api/tasks'
import { metaApi } from '@/api/meta'
import { toApiError, type ApiError } from '@/api/http'
import { useAuthStore } from '@/stores/auth'
import { formatDate, formatDateTime, formatUrgeSummary } from '@/utils/format'
import { PRIORITY_LABELS } from '@/types/enums'
import { DISABLED_SUFFIX, projectView } from '@/utils/projectOptions'
import { themeView } from '@/utils/themeOptions'
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
let anchorTimer: number | undefined

const mentionOnly = computed(() => detail.value?.permissions?.mentionOnly === true)
const mentionerName = computed(
  () => progress.value.find((row) => row.mentionedMe)?.author?.name || '同事',
)

const taskId = computed(() => Number(route.params.id))
const canEdit = computed(() => auth.role === 'ADMIN')
const owners = computed<UserOption[]>(() => (Array.isArray(meta.value?.owners) ? meta.value!.owners : []))
const meetings = computed(() => (Array.isArray(meta.value?.meetings) ? meta.value!.meetings : []))
const sourceTypes = computed(() => (Array.isArray(meta.value?.sourceTypes) ? meta.value!.sourceTypes : []))

/**
 * 项目位（AC-7）：只有「已绑项目」与「改形历史（形态为项目但无项目）」要渲染，
 * 会议/说明来源不产生任何元素；判定集中在 projectView，页面不自己比 projectId。
 */
const sourceProject = computed(() => projectView(detail.value?.source))
/** 项目名完整展示（20 字不截断），只有停用才加后缀，未知（null）按启用显示 */
const sourceProjectName = computed(() => {
  const view = sourceProject.value
  if (!view) return ''
  return view.name || `项目 #${detail.value?.source?.projectId ?? '—'}`
})

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

const cancelled = computed(() => detail.value?.status === 'CANCELLED')

/** 任务行自带的主题快照；未挂主题时 unspecified=true，来源区不渲染标签 */
const themeInfo = computed(() => themeView(detail.value?.theme))

/** 计划行只在填了计划开始或已逾期时出现（无数据不占位） */
const planText = computed(() => {
  const task = detail.value
  if (!task) return ''
  if (task.plannedStartDate) {
    return `计划：${formatDate(task.plannedStartDate)} → ${formatDate(task.dueDate)}`
  }
  if (task.isOverdue) {
    return `计划：未填写（甘特按创建日推算） · 截止 ${formatDate(task.dueDate)}`
  }
  return ''
})

const urgeSummary = computed(() => {
  const task = detail.value
  if (!task) return '还没有催办'
  return formatUrgeSummary(task.urgeCount ?? 0, task.lastUrgedAt ?? null)
})

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
            v-if="!mentionOnly"
            :task-id="detail.id"
            :owner-name="detail.owner?.name"
            :last-urged-at="detail.lastUrgedAt ?? null"
            :can-urge="Boolean(detail.permissions?.canUrge)"
            :disabled-reason="detail.permissions?.disabledReasons?.urge"
            :urge-count="detail.urgeCount"
            @urged="onUrged"
          />
          <TaskActions
            v-if="!mentionOnly"
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
          <!-- ① 状态步进：五步闭环条（DESIGN §3.6），只描述下一步，不渲染按钮 -->
          <section class="panel">
            <h2 class="panel__title">进度</h2>
            <TaskStatusFlow
              :status="detail.status"
              :owner-name="detail.owner?.name"
              :dispatcher-name="detail.dispatcher?.name"
            />
            <p v-if="cancelled && detail.completedAt" class="cancelled">
              取消时间 {{ formatDateTime(detail.completedAt) }}
            </p>
          </section>

          <!-- ② 来源 -->
          <section class="panel">
            <h2 class="panel__title">来源</h2>
            <div class="source">
              <div class="source__head">
                <!-- 来源名只由 SourceTag 渲染一次（缺名称时它自己兜底「未知来源（CODE）」）；v-else 仅补空源占位 -->
                <SourceTag
                  v-if="detail.source"
                  :code="detail.source.type"
                  :name="detail.source.typeLabel"
                  :color="detail.source.color"
                  :soft-color="detail.source.softColor"
                  :border-color="detail.source.borderColor"
                  :meeting-title="detail.source.meetingTitle"
                />
                <span v-else class="source__type">—</span>
                <!-- 项目位（AC-7）：已绑项目可下钻到任务池同名筛选；改形历史「未指定项目」不可点；会议/说明不渲染 -->
                <RouterLink
                  v-if="detail.source?.projectId != null"
                  :to="{ path: '/tasks', query: { project: String(detail.source.projectId) } }"
                  class="source__project"
                >
                  {{ sourceProjectName }}<span v-if="sourceProject && !sourceProject.enabled">{{ DISABLED_SUFFIX }}</span>
                </RouterLink>
                <ProjectTag v-else-if="sourceProject?.unspecified" unspecified />
                <!-- 主题位：未挂主题不渲染（不产生空标签）；三色来自任务行快照 -->
                <ThemeTag
                  v-if="!themeInfo.unspecified"
                  :name="themeInfo.name"
                  :color="themeInfo.color"
                  :soft-color="themeInfo.softColor"
                  :border-color="themeInfo.borderColor"
                  :enabled="themeInfo.enabled"
                />
              </div>
              <!-- 形态以「是否绑定会议」为准，不按来源编码判断：自定义会议型来源同样渲染（D-01 / AC-5） -->
              <template v-if="detail.source?.meetingId != null">
                <p class="source__meeting">
                  <RouterLink
                    v-if="auth.role === 'ADMIN'"
                    :to="`/meetings/${detail.source.meetingId}`"
                    class="source__link"
                  >
                    {{ detail.source.meetingTitle || `会议 #${detail.source.meetingId}` }}
                  </RouterLink>
                  <span v-else>{{ detail.source.meetingTitle || '会议' }}</span>
                </p>
                <p v-if="auth.role !== 'ADMIN'" class="source__hint">仅管理员可查看会议</p>
                <blockquote v-if="detail.source.note" class="source__note">
                  {{ detail.source.note }}
                </blockquote>
              </template>
              <p v-else class="source__note">{{ detail.source?.note || '没有填写来源说明' }}</p>
              <p class="source__tip">来源是创建时的快照，会议后续改动不会影响这条任务。</p>
              <p v-if="planText" class="source__plan">{{ planText }}</p>
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

          <!-- ④ 进度与流转（合并时间线） -->
          <section class="panel">
            <div class="panel__head">
              <h2 class="panel__title">进度与流转</h2>
              <span class="panel__count num" :class="{ 'is-limit': progressTotal >= 200 }">
                进度 {{ progressTotal }}/200
              </span>
            </div>

            <ProgressComposer
              :task-id="detail.id"
              :total="progressTotal"
              @created="onProgressCreated"
            />

            <div v-if="progressLoading" class="tl-skeleton" aria-busy="true" aria-live="polite">
              <span class="visually-hidden">正在加载进度</span>
              <div v-for="n in 3" :key="`tl-sk-${n}`" class="tl-skeleton__row">
                <span class="tl-skeleton__dot" aria-hidden="true" />
                <span class="sk-block tl-skeleton__line" />
              </div>
            </div>

            <template v-else>
              <div v-if="progressError" class="tl-error" role="alert">
                <span>进度加载失败，流转记录仍可查看。</span>
                <button type="button" class="tl-error__retry" @click="loadProgress">重试</button>
              </div>
              <ProgressTimeline :progress="progress" :events="detail.events ?? []" />
            </template>
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
        :source-types="sourceTypes"
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
/* 五步闭环条的样式收在 TaskStatusFlow 内（含 ≤640 竖排），本页不再保留第二套 .steps */
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
  flex-wrap: wrap;
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
/* 项目名是详情里的可读事实：20 字不截断、不省略，只做换行（AC-7） */
.source__project {
  color: var(--c-primary);
  font-size: var(--fs-body-sm);
  font-weight: 600;
  text-decoration: none;
  overflow-wrap: anywhere;
  border-radius: var(--r-sm);
}
.source__project:hover {
  text-decoration: underline;
}
.source__project:focus-visible {
  outline: 2px solid var(--focus-ring);
  outline-offset: 2px;
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
.source__hint {
  margin: 0;
  font-size: var(--fs-micro);
  color: var(--c-ink-400);
}
.source__plan {
  margin: 0;
  font-size: var(--fs-micro);
  color: var(--c-ink-500);
  font-variant-numeric: tabular-nums;
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

/* ---- 被 @ 提示条（DESIGN §1.3 / §3.8） ---- */
.mention-banner {
  display: flex;
  align-items: center;
  gap: var(--sp-2);
  min-height: 40px;
  padding: var(--sp-2) var(--sp-3);
  border: 1px solid var(--c-info-border);
  border-radius: var(--r-md);
  background: var(--c-info-soft);
  color: var(--c-info);
}
.mention-banner__icon {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 16px;
  height: 16px;
  flex: none;
  border-radius: var(--r-pill);
  background: var(--c-surface);
  font-size: var(--fs-micro);
  font-weight: 700;
}
.mention-banner__text {
  flex: 1 1 auto;
  min-width: 0;
  margin: 0;
  font-size: var(--fs-body-sm);
  line-height: 20px;
}
.mention-banner__close {
  flex: none;
  width: 24px;
  height: 24px;
  padding: 0;
  border: 0;
  border-radius: var(--r-sm);
  background: none;
  color: var(--c-info);
  font-family: inherit;
  font-size: var(--fs-h3);
  line-height: 1;
  cursor: pointer;
}
.mention-banner__close:hover {
  background: var(--c-surface);
}
.mention-banner__close:focus-visible {
  outline: 2px solid var(--c-info);
  outline-offset: 1px;
}

/* ---- 进度与流转面板头 ---- */
.panel__head {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  gap: var(--sp-3);
  margin-bottom: var(--sp-3);
}
.panel__head .panel__title {
  margin-bottom: 0;
}
.panel__count {
  color: var(--c-ink-500);
  font-size: var(--fs-caption);
  line-height: 18px;
  white-space: nowrap;
}
.panel__count.is-limit {
  color: var(--c-warn);
  font-weight: 600;
}

/* ---- 合并时间线的加载与错误（DESIGN §1.5） ---- */
.tl-skeleton {
  display: flex;
  flex-direction: column;
  gap: var(--sp-3);
}
.tl-skeleton__row {
  display: flex;
  align-items: center;
  gap: var(--sp-3);
}
.tl-skeleton__dot {
  width: 8px;
  height: 8px;
  flex: none;
  border-radius: 50%;
  background: var(--c-line);
}
.tl-skeleton__line {
  width: 62%;
  height: 12px;
  border-radius: var(--r-sm);
}
.tl-error {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--sp-3);
  margin-bottom: var(--sp-3);
  padding: var(--sp-2) var(--sp-3);
  border: 1px solid var(--c-danger-border);
  border-radius: var(--r-sm);
  background: var(--c-danger-soft);
  color: var(--c-danger-text);
  font-size: var(--fs-caption);
}
.tl-error__retry {
  flex: none;
  padding: 0;
  border: 0;
  background: none;
  color: var(--c-danger-text);
  font-family: inherit;
  font-size: var(--fs-caption);
  font-weight: 600;
  text-decoration: underline;
  cursor: pointer;
}
</style>
