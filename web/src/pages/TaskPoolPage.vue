<script setup lang="ts">
/**
 * 任务池（DESIGN §4.2 / AC-8）：所有任务的唯一入口，8 列 + 箭头 = 964px 不横滚。
 * 六个筛选条件全部双向同步进 URL：刷新、收藏、分享之后看到的还是同一份结果；
 * 逾期与「今天」都由服务端算，这里没有任何日期推算（AC-11 / AC-17）。
 */
import { computed, onMounted, onBeforeUnmount, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import PageHeader from '@/components/ui/PageHeader.vue'
import AppButton from '@/components/ui/AppButton.vue'
import Pagination from '@/components/ui/Pagination.vue'
import ErrorState from '@/components/ui/ErrorState.vue'
import EmptyState from '@/components/ui/EmptyState.vue'
import TaskTable from '@/components/task/TaskTable.vue'
import TaskFilterBar, { type PoolFilters } from '@/components/task/TaskFilterBar.vue'
import TaskFormDrawer from '@/components/task/TaskFormDrawer.vue'
import { useQuerySync, type QueryKinds } from '@/composables/useQuerySync'
import { taskApi, type TaskPageCounters } from '@/api/tasks'
import { metaApi } from '@/api/meta'
import { toApiError, type ApiError } from '@/api/http'
import { useAuthStore } from '@/stores/auth'
import { useToast } from '@/composables/useToast'
import type { SourceType, TaskStatus } from '@/types/enums'
import type { MetaOptions, TaskDetail, TaskFilter, TaskRow, UserOption } from '@/types/api'

const router = useRouter()
const auth = useAuthStore()
const toast = useToast()

const PAGE_SIZE = 20

interface PoolQuery {
  status: TaskStatus | ''
  owner: number | ''
  source: SourceType | ''
  meeting: number | ''
  overdue: boolean | ''
  q: string
  page: number
}

const DEFAULTS: PoolQuery = {
  status: '',
  owner: '',
  source: '',
  meeting: '',
  overdue: '',
  q: '',
  page: 1,
}

const KINDS: QueryKinds<PoolQuery> = {
  owner: 'number',
  meeting: 'number',
  overdue: 'boolean',
  page: 'number',
}

const { state, apply } = useQuerySync<PoolQuery>(DEFAULTS, KINDS)

const items = ref<TaskRow[]>([])
const total = ref(0)
const loading = ref(false)
const error = ref<ApiError | null>(null)
const counters = ref<TaskPageCounters>({ overdue: 0, pendingAccept: 0 })
const meta = ref<MetaOptions | null>(null)
const drawerOpen = ref(false)
const highlightId = ref<number | null>(null)
let highlightTimer: ReturnType<typeof setTimeout> | undefined
let loadTimer: ReturnType<typeof setTimeout> | undefined
let seq = 0

const canCreate = computed(() => auth.role === 'ADMIN')
const owners = computed<UserOption[]>(() => (Array.isArray(meta.value?.owners) ? meta.value!.owners : []))
const meetings = computed(() => (Array.isArray(meta.value?.meetings) ? meta.value!.meetings : []))

/** 筛选条与 URL 状态之间的唯一映射（URL 用短键，符合 DESIGN 的可分享形状） */
const filters = computed<PoolFilters>(() => ({
  status: state.value.status,
  ownerId: state.value.owner,
  sourceType: state.value.source,
  meetingId: state.value.meeting,
  overdue: state.value.overdue,
  keyword: state.value.q,
}))

const hasFilters = computed(
  () =>
    Boolean(state.value.status) ||
    state.value.owner !== '' ||
    Boolean(state.value.source) ||
    state.value.meeting !== '' ||
    state.value.overdue !== '' ||
    state.value.q.trim() !== '',
)

function buildFilter(): TaskFilter {
  return {
    status: state.value.status || undefined,
    ownerId: state.value.owner === '' ? undefined : state.value.owner,
    sourceType: state.value.source || undefined,
    meetingId: state.value.meeting === '' ? undefined : state.value.meeting,
    overdue: state.value.overdue === '' ? undefined : state.value.overdue,
    keyword: state.value.q.trim() || undefined,
    page: state.value.page,
    size: PAGE_SIZE,
  }
}

/** 只按「筛选 + 页码」的变化重取，URL 回灌相同值时不会重复请求 */
const requestKey = computed(() => JSON.stringify(buildFilter()))

async function load(): Promise<void> {
  const current = ++seq
  loading.value = true
  error.value = null
  try {
    const page = await taskApi.list(buildFilter())
    if (current !== seq) return
    items.value = Array.isArray(page?.items) ? page.items : []
    total.value = Number(page?.total ?? 0) || 0
  } catch (caught) {
    if (current !== seq) return
    error.value = toApiError(caught)
    items.value = []
    total.value = 0
  } finally {
    if (current === seq) loading.value = false
  }
  if (current !== seq) return
  try {
    counters.value = await taskApi.counters(buildFilter())
  } catch {
    counters.value = { overdue: 0, pendingAccept: 0 }
  }
}

function scheduleLoad(): void {
  if (loadTimer) clearTimeout(loadTimer)
  loadTimer = setTimeout(() => {
    void load()
  }, 220)
}

/** 筛选变化：先写 URL（apply 内部有 sameQuery 去重），requestKey 变化再取数据 */
watch(
  state,
  () => {
    if (state.value.page < 1) state.value.page = 1
    apply()
  },
  { deep: true },
)

watch(requestKey, () => scheduleLoad())

function onFilters(next: PoolFilters): void {
  state.value.status = next.status
  state.value.owner = next.ownerId
  state.value.source = next.sourceType
  state.value.meeting = next.meetingId
  state.value.overdue = next.overdue
  state.value.q = next.keyword
  state.value.page = 1
}

function clearFilters(): void {
  state.value.status = ''
  state.value.owner = ''
  state.value.source = ''
  state.value.meeting = ''
  state.value.overdue = ''
  state.value.q = ''
  state.value.page = 1
}

function changePage(page: number): void {
  state.value.page = Math.max(1, page)
}

function openRow(row: TaskRow): void {
  void router.push(`/tasks/${row.id}`)
}

function openCreate(): void {
  drawerOpen.value = true
}

function onCreated(detail: TaskDetail): void {
  drawerOpen.value = false
  if (detail?.id) {
    highlightId.value = detail.id
    if (highlightTimer) clearTimeout(highlightTimer)
    highlightTimer = setTimeout(() => {
      highlightId.value = null
    }, 1400)
  }
  state.value.page = 1
  void load()
}

async function loadMeta(): Promise<void> {
  try {
    const options = await metaApi.options()
    meta.value = options && typeof options === 'object' ? options : null
  } catch {
    // 后端 meta 未就绪时筛选条降级为空列表，不阻塞任务列表本身
    meta.value = null
    toast.warning('筛选项加载失败，任务列表仍可使用')
  }
}

onMounted(() => {
  void loadMeta()
  void load()
})

onBeforeUnmount(() => {
  if (loadTimer) clearTimeout(loadTimer)
  if (highlightTimer) clearTimeout(highlightTimer)
})
</script>

<template>
  <section class="pool">
    <PageHeader title="任务池" description="所有任务的唯一入口；逾期与待验收由服务端按同一个「今天」计算">
      <template #actions>
        <AppButton v-if="canCreate" variant="primary" @click="openCreate">新建任务</AppButton>
      </template>
    </PageHeader>

    <TaskFilterBar
      :model-value="filters"
      :owners="owners"
      :meetings="meetings"
      :loading="loading"
      @update:model-value="onFilters"
    />

    <ErrorState
      v-if="error"
      title="任务列表加载失败"
      :description="error.message || '请求没有完成，请稍后重试'"
      :trace-id="error.traceId"
      @retry="load"
    />

    <template v-else>
      <div class="pool__summary" aria-live="polite">
        <span class="num">共 {{ total }} 条</span>
        <span v-if="counters.overdue > 0" class="pool__stat pool__stat--danger">
          逾期 <span class="num">{{ counters.overdue }}</span>
        </span>
        <span v-if="counters.pendingAccept > 0" class="pool__stat">
          待验收 <span class="num">{{ counters.pendingAccept }}</span>
        </span>
        <span v-if="hasFilters" class="pool__scope">已按当前筛选统计</span>
      </div>

      <div class="pool__table">
        <TaskTable
          :items="items"
          :loading="loading"
          :highlight-id="highlightId"
          :empty-title="hasFilters ? '没有符合条件的任务' : '任务池还是空的'"
          :empty-hint="hasFilters ? '放宽或清除筛选条件后再看' : '从会议决议转任务，或直接新建一条'"
          @row-click="openRow"
        >
          <template #empty>
            <EmptyState
              :title="hasFilters ? '没有符合条件的任务' : '任务池还是空的'"
              :description="hasFilters ? '放宽或清除筛选条件后再看' : '从会议决议转任务，或直接新建一条'"
              size="sm"
            >
              <AppButton v-if="hasFilters" variant="secondary" size="compact" @click="clearFilters">
                清除筛选
              </AppButton>
            </EmptyState>
          </template>
        </TaskTable>
      </div>

      <div v-if="total > PAGE_SIZE" class="pool__pager">
        <Pagination
          :page="state.page"
          :size="PAGE_SIZE"
          :total="total"
          :size-options="[PAGE_SIZE]"
          @update:page="changePage"
        />
      </div>
    </template>

    <TaskFormDrawer
      :open="drawerOpen"
      :owners="owners"
      :meetings="meetings"
      @close="drawerOpen = false"
      @saved="onCreated"
    />
  </section>
</template>

<style scoped>
.pool {
  display: flex;
  flex-direction: column;
  gap: var(--sp-4);
}
.pool__summary {
  display: flex;
  align-items: center;
  gap: var(--sp-4);
  min-height: 24px;
  font-size: var(--fs-body-sm);
  color: var(--c-ink-600);
}
.pool__stat--danger {
  color: var(--c-danger-text);
  font-weight: 600;
}
.pool__scope {
  color: var(--c-ink-400);
  font-size: var(--fs-caption);
}
.pool__table {
  border: 1px solid var(--c-line);
  border-radius: var(--r-lg);
  background: var(--c-surface);
  overflow: hidden;
}
.pool__pager {
  display: flex;
  justify-content: flex-end;
}
</style>
