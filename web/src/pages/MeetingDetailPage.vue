<script setup lang="ts">
/**
 * 会议详情（§4.7 / AC-3 / AC-4 / AC-5）。
 * - 决议事项逐项录入并在单元格内校验：错误回填到「哪一行哪一列」，而不是页面顶部一句话；
 * - 只有未转任务的事项可勾选、可编辑、可删除，转任务后来源快照锁定；
 * - 转任务部分成功是正常结果：toast 同时报 created 与 skipped（已转过的项跳过，幂等）。
 */
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import PageHeader from '@/components/ui/PageHeader.vue'
import AppButton from '@/components/ui/AppButton.vue'
import AppInput from '@/components/ui/AppInput.vue'
import AppSelect from '@/components/ui/AppSelect.vue'
import AppSearchSelect from '@/components/ui/AppSearchSelect.vue'
import AppTextarea from '@/components/ui/AppTextarea.vue'
import AppDatePicker from '@/components/ui/AppDatePicker.vue'
import AppBadge from '@/components/ui/AppBadge.vue'
import StatusBadge from '@/components/ui/StatusBadge.vue'
import OverduePill from '@/components/ui/OverduePill.vue'
import ConfirmDialog from '@/components/ui/ConfirmDialog.vue'
import Modal from '@/components/ui/Modal.vue'
import UserMultiSelect from '@/components/ui/UserMultiSelect.vue'
import MeetingTaskProgress from '@/components/meeting/MeetingTaskProgress.vue'
import ConvertTasksModal from '@/components/meeting/ConvertTasksModal.vue'
import ErrorState from '@/components/ui/ErrorState.vue'
import EmptyState from '@/components/ui/EmptyState.vue'
import { meetingApi, convertSummary } from '@/api/meetings'
import { metaApi } from '@/api/meta'
import { toApiError, type ApiError } from '@/api/http'
import { useToast } from '@/composables/useToast'
import { formatDate } from '@/utils/format'
import type { BadgeTone } from '@/types/enums'
import type {
  ConvertResult,
  DecisionItemRequest,
  DecisionItemRow,
  MeetingDetail,
  MeetingRequest,
  TaskRow,
  ThemeOptionRow,
  UserOption,
} from '@/types/api'

const route = useRoute()
const router = useRouter()
const toast = useToast()

const meetingId = computed(() => Number(route.params.id))

const meeting = ref<MeetingDetail | null>(null)
const loading = ref(true)
const error = ref<ApiError | null>(null)
const notFound = ref(false)

const owners = ref<UserOption[]>([])
const activeOwners = computed(() => owners.value.filter((user) => user.status === 'ACTIVE'))

const selected = ref<number[]>([])
let autoSelect = true

const items = computed<DecisionItemRow[]>(() => (Array.isArray(meeting.value?.items) ? meeting.value!.items : []))
const unconverted = computed(() => items.value.filter((item) => !item.converted))
const archived = computed(() => meeting.value?.archived === true)
const editable = computed(() => !archived.value)

/* ------------------------------------------------- 任务进度区（AC-4 单一口径） */

/**
 * 进度区的唯一数据源：`GET /meetings/{id}/tasks`。
 * 页头不再显示「已转任务 / 未完成」两个数（原先读会议行的 taskCount/unfinishedCount），
 * 否则同一个「未完成」会因刷新时机不同而出现两个数字。
 */
const tasks = ref<TaskRow[]>([])
const tasksLoading = ref(false)
const tasksError = ref<ApiError | null>(null)
const themes = ref<ThemeOptionRow[]>([])
let tasksSeq = 0

async function loadTasks(): Promise<void> {
  const current = ++tasksSeq
  tasksLoading.value = true
  tasksError.value = null
  try {
    const list = await meetingApi.tasks(meetingId.value)
    if (current !== tasksSeq) return
    tasks.value = Array.isArray(list) ? list : []
  } catch (err) {
    if (current !== tasksSeq) return
    tasks.value = []
    tasksError.value = toApiError(err)
  } finally {
    if (current === tasksSeq) tasksLoading.value = false
  }
}

/** 页头徽标与进度区同源：都由 tasks 算，不再读会议行上的计数 */
const progressBadge = computed<{ text: string; tone: BadgeTone }>(() => {
  if (tasks.value.length === 0) return { text: '待派发', tone: 'neutral' }
  const unfinished = tasks.value.filter((task) => task.status !== 'DONE' && task.status !== 'CANCELLED').length
  if (unfinished === 0) return { text: '已闭环', tone: 'success' }
  return { text: `未完成 ${unfinished} 条`, tone: 'warning' }
})

const allSelected = computed(
  () => unconverted.value.length > 0 && selected.value.length === unconverted.value.length,
)
const someSelected = computed(() => selected.value.length > 0 && !allSelected.value)

/* ------------------------------------------------------------ 数据加载 */

async function load(): Promise<void> {
  loading.value = true
  error.value = null
  notFound.value = false
  try {
    const detail = await meetingApi.get(meetingId.value)
    meeting.value = detail
    if (autoSelect && !detail.archived) {
      selected.value = detail.items.filter((item) => !item.converted).map((item) => item.id)
    } else {
      const selectable = new Set(detail.items.filter((item) => !item.converted).map((item) => item.id))
      selected.value = selected.value.filter((id) => selectable.has(id))
    }
    void loadTasks()
    applyItemHighlight()
  } catch (err) {
    const apiError = toApiError(err)
    error.value = apiError
    notFound.value = apiError.status === 404
    meeting.value = null
  } finally {
    loading.value = false
  }
}

onMounted(async () => {
  void load()
  try {
    const options = await metaApi.options()
    owners.value = options.owners
    themes.value = Array.isArray(options.themes) ? options.themes : []
  } catch {
    /* 负责人下拉/主题选项依赖元数据；失败时仍可浏览与录入已有事项 */
  }
})

watch(meetingId, () => {
  autoSelect = true
  selected.value = []
  tasks.value = []
  cancelEdit()
  void load()
})

/* -------------------------------------------------------- 会议信息编辑 */

const editOpen = ref(false)
const savingMeeting = ref(false)
const meetingForm = ref<{ title: string; meetingDate: string; host: string; note: string; attendeeIds: number[] }>({
  title: '',
  meetingDate: '',
  host: '',
  note: '',
  attendeeIds: [],
})
const meetingErrors = ref<Record<string, string>>({})
const meetingTopError = ref('')

/** 主持人仍是姓名文本；选项=启用用户，非系统用户走 allowCustom 原样落库 */
const hostOptions = computed(() => activeOwners.value.map((user) => ({ label: user.name, value: user.name })))

function onHostChange(value: string): void {
  meetingForm.value.host = value
  const matched = activeOwners.value.find((user) => user.name === value.trim())
  if (matched && !meetingForm.value.attendeeIds.includes(matched.id)) {
    meetingForm.value.attendeeIds.push(matched.id)
  }
}

function openMeetingEdit(): void {
  const current = meeting.value
  if (!current) return
  meetingForm.value = {
    title: current.title,
    meetingDate: current.meetingDate,
    host: current.host,
    note: current.note ?? '',
    attendeeIds: current.attendees.map((user) => user.id),
  }
  meetingErrors.value = {}
  meetingTopError.value = ''
  editOpen.value = true
}

async function saveMeeting(): Promise<void> {
  const current = meeting.value
  if (!current) return
  const payload: MeetingRequest = {
    title: meetingForm.value.title.trim(),
    meetingDate: meetingForm.value.meetingDate,
    host: meetingForm.value.host.trim(),
    attendeeIds: [...meetingForm.value.attendeeIds],
    note: meetingForm.value.note.trim() || undefined,
  }
  const local: Record<string, string> = {}
  if (!payload.title) local.title = '请填写会议标题'
  if (!payload.meetingDate) local.meetingDate = '请选择会议日期'
  if (!payload.host) local.host = '请填写主持人'
  meetingErrors.value = local
  meetingTopError.value = ''
  if (Object.keys(local).length) return
  savingMeeting.value = true
  try {
    const updated = await meetingApi.update(current.id, payload)
    meeting.value = updated
    editOpen.value = false
    toast.success('会议信息已更新')
  } catch (err) {
    applyMeetingErrors(toApiError(err))
  } finally {
    savingMeeting.value = false
  }
}

function applyMeetingErrors(apiError: ApiError): void {
  const next: Record<string, string> = {}
  for (const field of apiError.fieldErrors ?? []) {
    if (!next[field.field]) next[field.field] = field.message
  }
  meetingErrors.value = next
  meetingTopError.value = Object.keys(next).length ? '' : apiError.message
}

const archiveOpen = ref(false)
const archiving = ref(false)

async function confirmArchive(): Promise<void> {
  const current = meeting.value
  if (!current) return
  archiving.value = true
  try {
    await meetingApi.archive(current.id)
    archiveOpen.value = false
    toast.success('会议已归档，其下任务与来源保留')
    void load()
  } catch (err) {
    toast.error(toApiError(err).message)
  } finally {
    archiving.value = false
  }
}

/* ------------------------------------------------- 决议事项行内编辑 / 新增 */

type EditingKey = number | 'new'

interface ItemDraft {
  description: string
  ownerId: string
  dueDate: string
  deliveryNote: string
}

const editing = ref<EditingKey | null>(null)
const draft = ref<ItemDraft>({ description: '', ownerId: '', dueDate: '', deliveryNote: '' })
const cellErrors = ref<Record<string, string>>({})
const rowBusy = ref(false)

function draftFrom(item: DecisionItemRow): ItemDraft {
  return {
    description: item.description,
    ownerId: String(item.owner.id),
    dueDate: item.dueDate,
    deliveryNote: item.deliveryNote ?? '',
  }
}

function startEdit(item: DecisionItemRow): void {
  if (!editable.value || item.converted) return
  editing.value = item.id
  draft.value = draftFrom(item)
  cellErrors.value = {}
}

function startAdd(): void {
  if (!editable.value) return
  editing.value = 'new'
  draft.value = { description: '', ownerId: '', dueDate: '', deliveryNote: '' }
  cellErrors.value = {}
}

function cancelEdit(): void {
  editing.value = null
  cellErrors.value = {}
}

function cellError(key: EditingKey, field: string): string | undefined {
  return cellErrors.value[`${key}:${field}`]
}

function validateDraft(key: EditingKey): boolean {
  const next: Record<string, string> = {}
  const description = draft.value.description.trim()
  if (!description) next[`${key}:description`] = '请填写事项描述'
  else if (description.length > 100) next[`${key}:description`] = '事项描述不能超过 100 字'
  if (!draft.value.ownerId) next[`${key}:ownerId`] = '请选择负责人'
  if (!draft.value.dueDate) next[`${key}:dueDate`] = '请选择截止日期'
  if (draft.value.deliveryNote.length > 500) next[`${key}:deliveryNote`] = '交付说明不能超过 500 字'
  cellErrors.value = next
  return Object.keys(next).length === 0
}

function toRequest(): DecisionItemRequest {
  return {
    description: draft.value.description.trim(),
    ownerId: draft.value.ownerId ? Number(draft.value.ownerId) : null,
    dueDate: draft.value.dueDate,
    deliveryNote: draft.value.deliveryNote.trim() || undefined,
  }
}

/** 焦点回到出错单元格（AC-3）：整行不刷新，用户改一处即可再提交 */
async function focusFirstCellError(key: EditingKey): Promise<void> {
  const order = ['description', 'ownerId', 'dueDate', 'deliveryNote']
  const field = order.find((name) => cellErrors.value[`${key}:${name}`])
  if (!field) return
  await nextTick()
  const cell = document.querySelector<HTMLElement>(`[data-cell="${key}:${field}"]`)
  const target = cell?.querySelector<HTMLElement>('input, textarea, select') ?? null
  target?.focus()
}

/** 连续录入：Enter 保存成功后立刻开下一行并把焦点放到「描述」 */
async function saveAndContinue(): Promise<void> {
  if (rowBusy.value) return
  const ok = await saveDraft()
  if (!ok) return
  startAdd()
  await nextTick()
  const cell = document.querySelector<HTMLElement>('[data-cell="new:description"]')
  cell?.querySelector<HTMLElement>('input, textarea')?.focus()
}

/**
 * 单条保存：服务端 fieldErrors 的 index 指向本次请求的那一行，直接回填到当前行。
 * 返回是否保存成功——`Enter` 连续录入要靠它决定「开下一行」还是「停在出错单元格」。
 */
async function saveDraft(): Promise<boolean> {
  const key = editing.value
  if (key === null) return false
  if (!validateDraft(key)) {
    void focusFirstCellError(key)
    return false
  }
  rowBusy.value = true
  try {
    if (key === 'new') {
      await meetingApi.addItem(meetingId.value, toRequest())
      toast.success('决议事项已添加')
      autoSelect = false
    } else {
      await meetingApi.updateItem(key, toRequest())
      toast.success('决议事项已更新')
    }
    editing.value = null
    cellErrors.value = {}
    await load()
    return true
  } catch (err) {
    const apiError = toApiError(err)
    const next: Record<string, string> = {}
    for (const field of apiError.fieldErrors ?? []) {
      next[`${key}:${field.field}`] = field.message
    }
    if (Object.keys(next).length) {
      cellErrors.value = next
      void focusFirstCellError(key)
    } else {
      toast.error(apiError.message)
    }
    return false
  } finally {
    rowBusy.value = false
  }
}

const deleteTarget = ref<DecisionItemRow | null>(null)
const deleting = ref(false)

async function confirmDelete(): Promise<void> {
  const target = deleteTarget.value
  if (!target) return
  deleting.value = true
  try {
    await meetingApi.deleteItem(target.id)
    deleteTarget.value = null
    toast.success('决议事项已删除')
    await load()
  } catch (err) {
    toast.error(toApiError(err).message)
  } finally {
    deleting.value = false
  }
}

/* --------------------------------------------------------------- 勾选转任务 */

function toggleRow(id: number, checked: boolean): void {
  if (checked) {
    if (!selected.value.includes(id)) selected.value = [...selected.value, id]
    return
  }
  selected.value = selected.value.filter((value) => value !== id)
}

function toggleAll(checked: boolean): void {
  selected.value = checked ? unconverted.value.map((item) => item.id) : []
}

async function convert(): Promise<void> {
  if (!selected.value.length || convertOpen.value) return
  // 转任务要挂主题与计划开始（AC-9），一律走弹窗统一填写；这里只负责打开
  convertOpen.value = true
}

const convertOpen = ref(false)

/** 弹窗要展示的、本次真正要转的决议（勾选 + 未转） */
const convertTargets = computed(() => unconverted.value.filter((item) => selected.value.includes(item.id)))

async function onConverted(result: ConvertResult): Promise<void> {
  convertOpen.value = false
  toast.success(convertSummary(result))
  autoSelect = false
  selected.value = []
  await load()
}

function itemLink(item: DecisionItemRow): string | null {
  return item.converted && item.taskId ? `/tasks/${item.taskId}` : null
}

function openTask(id: number): void {
  void router.push(`/tasks/${id}`)
}

function isUrl(value: string): boolean {
  return /^https?:\/\//i.test(value)
}

/* ------------------------------------------------ 从任务详情跳回并高亮（AC-5） */

const highlightedItem = ref<number | null>(null)
let highlightTimer: ReturnType<typeof setTimeout> | undefined

/**
 * `?item=<决议项 id>`：滚动到该决议行并高亮 1600ms（DESIGN §3.1 的短暂底色，不改变行高）。
 * 只有系统用户能看到会议详情（AC-5），所以不需要再按角色分支。
 */
function applyItemHighlight(): void {
  const raw = route.query.item
  const value = Array.isArray(raw) ? raw[0] : raw
  const id = Number(value)
  if (!value || !Number.isFinite(id) || id <= 0) return
  void nextTick(() => {
    const target = document.querySelector<HTMLElement>(`[data-item="${id}"]`)
    if (!target) return
    target.scrollIntoView?.({ block: 'center', behavior: 'smooth' })
    highlightedItem.value = id
    clearTimeout(highlightTimer)
    highlightTimer = setTimeout(() => {
      highlightedItem.value = null
    }, 1600)
  })
}

onBeforeUnmount(() => clearTimeout(highlightTimer))
</script>

<template>
  <section class="page">
    <ErrorState
      v-if="error"
      :title="notFound ? '会议不存在' : '会议加载失败'"
      :description="notFound ? '该会议可能已被移除，或链接有误。' : error.message"
      :trace-id="error.traceId ?? undefined"
      @retry="load"
    />
    <template v-else>
      <div v-if="loading && !meeting" class="head-skeleton sk-block" />
      <template v-else-if="meeting">
        <PageHeader :title="meeting.title" :description="`${formatDate(meeting.meetingDate)} · 主持人 ${meeting.host}`">
          <template #meta>
            <div class="meta">
              <AppBadge :tone="progressBadge.tone" size="sm">{{ progressBadge.text }}</AppBadge>
              <span v-if="meeting.attendees.length" class="meta__text">
                参会人：{{ meeting.attendees.map((user) => user.name).join('、') }}
              </span>
              <span v-else class="meta__text">参会人未登记</span>
              <span v-if="meeting.note" class="meta__text">备注：{{ meeting.note }}</span>
            </div>
          </template>
          <template #actions>
            <AppButton variant="secondary" :disabled="archived" @click="openMeetingEdit">编辑</AppButton>
            <AppButton v-if="!archived" variant="secondary" @click="archiveOpen = true">归档</AppButton>
          </template>
        </PageHeader>

        <p v-if="archived" class="archived-note" role="status">
          会议已归档：决议事项只读；已生成的任务与来源信息不受影响。
        </p>

        <div class="summary">
          <span class="summary__item"><b class="num">{{ items.length }}</b> 项决议</span>
          <span class="summary__item summary__hint">勾选未转任务的决议事项，一键派给负责人；任务进度见下方进度区</span>
        </div>

        <div class="table-wrap">
          <table class="items">
            <colgroup>
              <col style="width: 40px" />
              <col />
              <col style="width: 120px" />
              <col style="width: 132px" />
              <col style="width: 160px" />
              <col style="width: 140px" />
              <col style="width: 96px" />
            </colgroup>
            <thead>
              <tr>
                <th scope="col" class="col-check">
                  <input
                    type="checkbox"
                    aria-label="全选未转任务的决议事项"
                    :checked="allSelected"
                    :indeterminate="someSelected"
                    :disabled="!editable || !unconverted.length"
                    @change="toggleAll(($event.target as HTMLInputElement).checked)"
                  />
                </th>
                <th scope="col">决议事项</th>
                <th scope="col">负责人</th>
                <th scope="col">截止日期</th>
                <th scope="col">交付说明</th>
                <th scope="col">转化状态</th>
                <th scope="col" class="col-actions">操作</th>
              </tr>
            </thead>
            <tbody>
              <tr
                v-for="item in items"
                :key="item.id"
                :data-item="item.id"
                :class="{ 'is-locked': item.converted, 'is-highlight': highlightedItem === item.id }"
              >
                <td class="col-check">
                  <input
                    type="checkbox"
                    :aria-label="`选择决议事项：${item.description}`"
                    :checked="selected.includes(item.id)"
                    :disabled="item.converted || !editable"
                    @change="toggleRow(item.id, ($event.target as HTMLInputElement).checked)"
                  />
                </td>
                <template v-if="editing === item.id">
                  <td class="cell-edit">
                    <AppInput
                      v-model="draft.description"
                      size="compact"
                      :maxlength="100"
                      :invalid="!!cellError(item.id, 'description')"
                      :error="cellError(item.id, 'description')"
                    />
                  </td>
                  <td class="cell-edit">
                    <AppSelect
                      :model-value="draft.ownerId"
                      :options="activeOwners.map((user) => ({ label: user.name, value: String(user.id) }))"
                      placeholder="选择负责人"
                      size="compact"
                      :invalid="!!cellError(item.id, 'ownerId')"
                      :error="cellError(item.id, 'ownerId')"
                      @update:model-value="draft.ownerId = $event"
                    />
                  </td>
                  <td class="cell-edit">
                    <AppDatePicker
                      v-model="draft.dueDate"
                      size="compact"
                      :invalid="!!cellError(item.id, 'dueDate')"
                      :error="cellError(item.id, 'dueDate')"
                    />
                  </td>
                  <td class="cell-edit">
                    <AppInput
                      v-model="draft.deliveryNote"
                      size="compact"
                      :maxlength="500"
                      placeholder="交付物/链接"
                      :invalid="!!cellError(item.id, 'deliveryNote')"
                      :error="cellError(item.id, 'deliveryNote')"
                    />
                  </td>
                  <td colspan="2" class="cell-edit cell-actions">
                    <AppButton size="compact" :loading="rowBusy" loading-text="保存中" @click="saveDraft">保存</AppButton>
                    <AppButton size="compact" variant="ghost" :disabled="rowBusy" @click="cancelEdit">取消</AppButton>
                  </td>
                </template>
                <template v-else>
                  <td class="cell-desc">{{ item.description }}</td>
                  <td>{{ item.owner.name }}</td>
                  <td>
                    <span class="num">{{ formatDate(item.dueDate) }}</span>
                    <OverduePill
                      v-if="item.converted && item.isOverdue"
                      :days="item.overdueDays"
                      class="cell-pill"
                    />
                  </td>
                  <td class="cell-note">
                    <a
                      v-if="item.deliveryNote && isUrl(item.deliveryNote)"
                      :href="item.deliveryNote"
                      target="_blank"
                      rel="noopener noreferrer"
                      class="cell-link"
                    >{{ item.deliveryNote }}</a>
                    <span v-else-if="item.deliveryNote">{{ item.deliveryNote }}</span>
                    <span v-else class="muted">—</span>
                  </td>
                  <td>
                    <RouterLink v-if="itemLink(item) && item.taskStatus" :to="itemLink(item)!" class="status-link">
                      <StatusBadge :status="item.taskStatus" size="sm" />
                    </RouterLink>
                    <span v-else-if="item.converted" class="muted">已转任务</span>
                    <span v-else class="muted">未转任务</span>
                  </td>
                  <td class="cell-actions">
                    <template v-if="editable && !item.converted">
                      <AppButton variant="ghost" size="compact" @click="startEdit(item)">编辑</AppButton>
                      <AppButton variant="ghost" size="compact" @click="deleteTarget = item">删除</AppButton>
                    </template>
                    <span v-else class="muted">—</span>
                  </td>
                </template>
              </tr>

              <tr v-if="editing === 'new'" class="is-new">
                <td class="col-check"><span class="muted">新</span></td>
                <td class="cell-edit" data-cell="new:description">
                  <AppInput
                    v-model="draft.description"
                    size="compact"
                    placeholder="事项描述，例如：完成接口联调并提交报告"
                    :maxlength="100"
                    :invalid="!!cellError('new', 'description')"
                    :error="cellError('new', 'description')"
                    @keydown.enter.prevent="saveAndContinue"
                    @keydown.esc.prevent="cancelEdit"
                  />
                </td>
                <td class="cell-edit" data-cell="new:ownerId">
                  <AppSelect
                    :model-value="draft.ownerId"
                    :options="activeOwners.map((user) => ({ label: user.name, value: String(user.id) }))"
                    placeholder="选择负责人"
                    size="compact"
                    :invalid="!!cellError('new', 'ownerId')"
                    :error="cellError('new', 'ownerId')"
                    @update:model-value="draft.ownerId = $event"
                    @keydown.enter.prevent="saveAndContinue"
                    @keydown.esc.prevent="cancelEdit"
                  />
                </td>
                <td class="cell-edit" data-cell="new:dueDate">
                  <AppDatePicker
                    v-model="draft.dueDate"
                    size="compact"
                    :invalid="!!cellError('new', 'dueDate')"
                    :error="cellError('new', 'dueDate')"
                    @keydown.enter.prevent="saveAndContinue"
                    @keydown.esc.prevent="cancelEdit"
                  />
                </td>
                <td class="cell-edit" data-cell="new:deliveryNote">
                  <AppInput
                    v-model="draft.deliveryNote"
                    size="compact"
                    placeholder="交付物/链接（可选）"
                    :maxlength="500"
                    :invalid="!!cellError('new', 'deliveryNote')"
                    :error="cellError('new', 'deliveryNote')"
                    @keydown.enter.prevent="saveAndContinue"
                    @keydown.esc.prevent="cancelEdit"
                  />
                </td>
                <td colspan="2" class="cell-edit cell-actions">
                  <AppButton size="compact" :loading="rowBusy" loading-text="保存中" @click="saveDraft">保存</AppButton>
                  <AppButton size="compact" variant="ghost" :disabled="rowBusy" @click="cancelEdit">取消</AppButton>
                </td>
              </tr>
              <tr v-if="editing === 'new'" class="key-hint-row">
                <td colspan="7" class="key-hint">Enter 保存并继续录入下一条 · Esc 取消</td>
              </tr>

              <tr v-if="!items.length && editing !== 'new'">
                <td colspan="7" class="cell-empty">
                  <EmptyState
                    title="还没有决议事项"
                    description="把开会结论逐条录入，负责人与截止日期齐全后才能转成任务。"
                  />
                </td>
              </tr>
            </tbody>
          </table>
        </div>

        <div class="items-foot">
          <AppButton variant="secondary" :disabled="!editable || editing === 'new'" @click="startAdd">
            + 添加决议事项
          </AppButton>
          <span class="items-foot__hint">四项必填：事项描述、负责人、截止日期、来源会议（本会议）</span>
        </div>

        <MeetingTaskProgress
          :items="tasks"
          :decision-total="items.length"
          :loading="tasksLoading"
          :error="tasksError"
          @retry="loadTasks"
          @open="openTask"
        />

        <div v-if="selected.length && editable" class="convert-bar">
          <span class="convert-bar__text">已选 {{ selected.length }} 项（均为未转任务）</span>
          <AppButton variant="primary" @click="convert">转为任务</AppButton>
        </div>
      </template>
    </template>

    <ConfirmDialog
      :open="archiveOpen"
      title="归档会议"
      description="归档后会议从列表隐藏，已生成的任务与来源信息不受影响。"
      confirm-label="归档会议"
      tone="danger"
      :loading="archiving"
      @confirm="confirmArchive"
      @close="archiveOpen = false"
    />

    <ConfirmDialog
      :open="deleteTarget !== null"
      title="删除决议事项"
      :description="deleteTarget ? `删除「${deleteTarget.description}」后不可恢复，且不会影响已生成的任务。` : ''"
      confirm-label="删除"
      tone="danger"
      :loading="deleting"
      @confirm="confirmDelete"
      @close="deleteTarget = null"
    />

    <Modal :open="editOpen" title="编辑会议" description="修改会议信息不会改动已生成任务的来源快照。" size="md" @close="editOpen = false">
      <form class="form" @submit.prevent="saveMeeting">
        <AppInput
          v-model="meetingForm.title"
          label="会议标题"
          required
          :invalid="!!meetingErrors.title"
          :error="meetingErrors.title"
        />
        <div class="form__row">
          <AppDatePicker
            v-model="meetingForm.meetingDate"
            label="会议日期"
            required
            :invalid="!!meetingErrors.meetingDate"
            :error="meetingErrors.meetingDate"
          />
          <AppSearchSelect
            :model-value="meetingForm.host"
            :options="hostOptions"
            label="主持人"
            placeholder="搜索或输入姓名"
            allow-custom
            required
            :invalid="!!meetingErrors.host"
            :error="meetingErrors.host"
            hint="不在系统里的人也能填，仅记录姓名，不参与提醒与权限"
            @update:model-value="meetingForm.host = $event"
            @change="onHostChange"
          />
        </div>
        <fieldset class="attendees">
          <legend class="attendees__legend">参会人</legend>
          <UserMultiSelect
            v-model="meetingForm.attendeeIds"
            :options="owners"
            placeholder="搜索并添加参会人"
            :invalid="!!meetingErrors.attendeeIds"
            :error="meetingErrors.attendeeIds"
          />
        </fieldset>
        <AppTextarea v-model="meetingForm.note" label="会议备注" :rows="2" :maxlength="200" placeholder="可选" />
        <p v-if="meetingTopError" class="form__error" role="alert">{{ meetingTopError }}</p>
        <div class="form__foot">
          <AppButton variant="secondary" :disabled="savingMeeting" @click="editOpen = false">取消</AppButton>
          <AppButton type="submit" :loading="savingMeeting" loading-text="保存中">保存</AppButton>
        </div>
      </form>
    </Modal>

    <ConvertTasksModal
      :open="convertOpen"
      :meeting-id="meetingId"
      :selected="convertTargets"
      :themes="themes"
      @close="convertOpen = false"
      @converted="onConverted"
    />

    <div class="back">
      <RouterLink to="/meetings" class="back__link">← 返回会议列表</RouterLink>
      <button v-if="error" type="button" class="back__link back__link--btn" @click="router.push('/meetings')">
        返回会议列表
      </button>
    </div>
  </section>
</template>

<style scoped>
.page {
  display: flex;
  flex-direction: column;
  gap: var(--sp-5);
  padding: var(--sp-6);
}
.meta {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: var(--sp-3);
  margin-top: var(--sp-2);
}
.meta__text {
  font-size: var(--fs-caption);
  color: var(--c-ink-500);
}
.head-skeleton {
  height: 96px;
  border-radius: var(--r-lg);
}
.archived-note {
  margin: 0;
  padding: var(--sp-3) var(--sp-4);
  border: 1px solid var(--c-line);
  border-left: 3px solid var(--c-ink-400);
  border-radius: var(--r-md);
  background: var(--c-surface-hover);
  font-size: var(--fs-body-sm);
  color: var(--c-ink-600);
}
.summary {
  display: flex;
  align-items: center;
  gap: var(--sp-5);
  padding: var(--sp-3) var(--sp-4);
  border: 1px solid var(--c-line);
  border-radius: var(--r-md);
  background: var(--c-surface);
  font-size: var(--fs-body-sm);
  color: var(--c-ink-600);
  flex-wrap: wrap;
}
.summary__item b {
  color: var(--c-ink-900);
  font-weight: 600;
  margin-right: 2px;
}
.summary__item b.is-danger {
  color: var(--c-danger-text);
}
.summary__hint {
  margin-left: auto;
  font-size: var(--fs-caption);
  color: var(--c-ink-500);
}
.table-wrap {
  border: 1px solid var(--c-line);
  border-radius: var(--r-lg);
  background: var(--c-surface);
  overflow-x: auto;
}
.items {
  width: 100%;
  min-width: 940px;
  border-collapse: separate;
  border-spacing: 0;
  table-layout: fixed;
}
.items thead th {
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
.items tbody td {
  padding: var(--sp-2) var(--sp-3);
  border-bottom: 1px solid var(--c-line-soft);
  font-size: var(--fs-body-sm);
  color: var(--c-ink-900);
  vertical-align: middle;
}
.items tbody tr:hover {
  background: var(--c-surface-hover);
}
.items tr.is-locked {
  background: var(--c-surface);
}
.col-check {
  text-align: center;
}
.col-actions {
  text-align: right;
}
.cell-desc {
  word-break: break-word;
}
.cell-note {
  color: var(--c-ink-600);
  word-break: break-all;
}
.cell-link {
  color: var(--c-primary);
  text-decoration: none;
}
.cell-link:hover {
  text-decoration: underline;
}
.cell-pill {
  margin-left: var(--sp-2);
}
.cell-actions {
  text-align: right;
  white-space: nowrap;
}
.cell-actions :deep(button) + :deep(button) {
  margin-left: var(--sp-1);
}
.cell-edit {
  padding: var(--sp-2) var(--sp-2);
}
.cell-edit :deep(.field) {
  margin: 0;
}
.cell-empty {
  padding: var(--sp-8) var(--sp-4);
}
/* 从任务详情跳回时的高亮：只改背景，不动行高，1600ms 后自动褪掉 */
.is-highlight > td {
  background: var(--c-primary-soft);
  animation: item-flash 1600ms var(--ease) forwards;
}
@keyframes item-flash {
  0%,
  70% {
    background: var(--c-primary-soft);
  }
  100% {
    background: transparent;
  }
}
.key-hint-row td.key-hint {
  padding: 0 var(--sp-2) var(--sp-2);
  font-size: var(--fs-caption);
  color: var(--c-ink-500);
}
.status-link {
  text-decoration: none;
}
.status-link:hover {
  text-decoration: underline;
}
.items-foot {
  display: flex;
  align-items: center;
  gap: var(--sp-4);
}
.items-foot__hint {
  font-size: var(--fs-caption);
  color: var(--c-ink-500);
}
.convert-bar {
  position: sticky;
  bottom: 0;
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--sp-4);
  height: 56px;
  padding: 0 var(--sp-5);
  border: 1px solid var(--c-line);
  border-radius: var(--r-md);
  background: var(--c-surface);
  box-shadow: var(--sh-2);
}
.convert-bar__text {
  font-size: var(--fs-body-sm);
  color: var(--c-ink-700);
}
.muted {
  color: var(--c-ink-400);
}
.form {
  display: flex;
  flex-direction: column;
  gap: var(--sp-4);
}
.form__row {
  display: grid;
  grid-template-columns: 1fr 1fr;
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
.attendees {
  margin: 0;
  padding: 0;
  border: 0;
}
.attendees__legend {
  font-size: var(--fs-body-sm);
  font-weight: 500;
  color: var(--c-ink-700);
  margin-bottom: var(--sp-2);
}
.back {
  display: flex;
  gap: var(--sp-4);
}
.back__link {
  font-size: var(--fs-body-sm);
  color: var(--c-primary);
  text-decoration: none;
}
.back__link:hover {
  text-decoration: underline;
}
.back__link--btn {
  border: 0;
  background: transparent;
  padding: 0;
  cursor: pointer;
}
</style>
