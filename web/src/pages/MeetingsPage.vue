<script setup lang="ts">
/**
 * 会议列表（§4.6）。会议只归档不删除：归档后从默认视图隐藏，但其下任务与来源快照不受影响（AC-5）。
 * 接口的 archived 是精确过滤而非「包含已归档」，所以开关切换的是「未归档视图 / 已归档视图」。
 */
import { computed, nextTick, onMounted, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import PageHeader from '@/components/ui/PageHeader.vue'
import AppButton from '@/components/ui/AppButton.vue'
import AppInput from '@/components/ui/AppInput.vue'
import AppDatePicker from '@/components/ui/AppDatePicker.vue'
import AppSelect from '@/components/ui/AppSelect.vue'
import AppSearchSelect from '@/components/ui/AppSearchSelect.vue'
import AppTextarea from '@/components/ui/AppTextarea.vue'
import AppBadge from '@/components/ui/AppBadge.vue'
import DataTable from '@/components/ui/DataTable.vue'
import ConfirmDialog from '@/components/ui/ConfirmDialog.vue'
import Modal from '@/components/ui/Modal.vue'
import Pagination from '@/components/ui/Pagination.vue'
import UserMultiSelect from '@/components/ui/UserMultiSelect.vue'
import ErrorState from '@/components/ui/ErrorState.vue'
import EmptyState from '@/components/ui/EmptyState.vue'
import { useQuerySync, type QueryKinds } from '@/composables/useQuerySync'
import { useToast } from '@/composables/useToast'
import { meetingApi } from '@/api/meetings'
import { metaApi } from '@/api/meta'
import { toApiError, type ApiError } from '@/api/http'
import { formatDate } from '@/utils/format'
import type { BadgeTone } from '@/types/enums'
import type { MeetingRow, UserOption } from '@/types/api'
import type { TableColumn } from '@/components/ui/DataTable.vue'

const router = useRouter()
const toast = useToast()

interface MeetingsQueryState {
  q: string
  archived: boolean
  page: number
}

const DEFAULTS: MeetingsQueryState = { q: '', archived: false, page: 1 }
const KINDS: QueryKinds<MeetingsQueryState> = { archived: 'boolean', page: 'number' }

const { state } = useQuerySync<MeetingsQueryState>(DEFAULTS, KINDS)

const rows = ref<MeetingRow[]>([])
const total = ref(0)
const size = ref(20)
const loading = ref(false)
const error = ref<ApiError | null>(null)
let seq = 0
let keywordTimer: ReturnType<typeof setTimeout> | undefined

const owners = ref<UserOption[]>([])

const columns: TableColumn[] = [
  { key: 'title', label: '会议标题' },
  { key: 'meetingDate', label: '会议日期', width: '116px' },
  { key: 'host', label: '主持人', width: '112px' },
  { key: 'taskCount', label: '已转任务', width: '100px', numeric: true },
  { key: 'unfinishedCount', label: '未完成', width: '96px', numeric: true },
  { key: 'closed', label: '闭环状态', width: '140px' },
  { key: 'actions', label: '操作', width: '96px', align: 'right' },
]

const archiveTarget = ref<MeetingRow | null>(null)
const archiving = ref(false)

/* ------------------------------------------------------------ 新建会议 */

/** 建会时的一行决议草稿：ownerId 用字符串以贴合 AppSelect 的 modelValue 形态 */
interface DecisionDraft {
  key: number
  description: string
  ownerId: string
  dueDate: string
}

let draftSeq = 0

const creating = ref(false)
const saving = ref(false)
const formError = ref('')
const fieldErrors = ref<Record<string, string>>({})
const form = ref({ title: '', meetingDate: '', host: '', note: '', attendeeIds: [] as number[] })
const decisions = ref<DecisionDraft[]>([])
/** 决议行错误键：`items:{行号}:{字段}`，避免与顶层字段同名互撞 */
const itemErrorKeys = computed(() => Object.keys(fieldErrors.value).filter((key) => key.startsWith('items:')))

const activeOwners = computed(() => owners.value.filter((user) => user.status === 'ACTIVE'))

/** 主持人仍是「姓名文本」（DB 零迁移），选项与可选项同源：启用用户的姓名 */
const hostOptions = computed(() => activeOwners.value.map((user) => ({ label: user.name, value: user.name })))

const ownerOptions = computed(() => activeOwners.value.map((user) => ({ label: user.name, value: String(user.id) })))

function openCreate(): void {
  form.value = { title: '', meetingDate: '', host: '', note: '', attendeeIds: [] }
  fieldErrors.value = {}
  formError.value = ''
  decisions.value = [newDecision()]
  creating.value = true
}

function newDecision(): DecisionDraft {
  draftSeq += 1
  return { key: draftSeq, description: '', ownerId: '', dueDate: '' }
}

function addDecision(): void {
  decisions.value.push(newDecision())
}

function removeDecision(index: number): void {
  decisions.value.splice(index, 1)
}

/**
 * 选中主持人后把它同时带进参会人（只加一次、已在列表不重复）。
 * 只在「选中系统用户」这一动作发生时触发；用户之后手动移除参会人，不会被悄悄加回来。
 */
function onHostChange(value: string): void {
  const name = value.trim()
  form.value.host = value
  const matched = activeOwners.value.find((user) => user.name === name)
  if (matched && !form.value.attendeeIds.includes(matched.id)) form.value.attendeeIds.push(matched.id)
}

function closeCreate(): void {
  if (saving.value) return
  creating.value = false
}

function itemError(index: number, field: string): string {
  return fieldErrors.value[`items:${index}:${field}`] ?? ''
}

/** 整行级提示（DESIGN §3.4：「第 2 行：截止日期不能为空」） */
function rowError(index: number): string {
  for (const field of ['row', 'description', 'ownerId', 'dueDate']) {
    const message = itemError(index, field)
    if (message) return `第 ${index + 1} 行：${message}`
  }
  return ''
}

/** 一行都没填的空行不提交：用户只是点了「添加」又没写，不该被后端当成缺描述 */
function isBlankDecision(row: DecisionDraft): boolean {
  return !row.description.trim() && !row.ownerId && !row.dueDate
}

async function focusFirstError(): Promise<void> {
  const first = itemErrorKeys.value[0]
  await nextTick()
  const host = first ? document.querySelector<HTMLElement>(`[data-cell="${first}"]`) : null
  const target = host?.querySelector<HTMLElement>('input, textarea, select') ?? null
  if (target) {
    target.focus()
    return
  }
  const top = document.querySelector<HTMLElement>('[data-create-error]')
  top?.focus()
}

async function submitCreate(): Promise<void> {
  fieldErrors.value = {}
  formError.value = ''
  const items = decisions.value
    .filter((row) => !isBlankDecision(row))
    .map((row) => ({
      description: row.description.trim(),
      ownerId: row.ownerId ? Number(row.ownerId) : null,
      dueDate: row.dueDate,
    }))
  const payload = {
    title: form.value.title.trim(),
    meetingDate: form.value.meetingDate,
    host: form.value.host.trim(),
    note: form.value.note.trim() || undefined,
    attendeeIds: [...form.value.attendeeIds],
    items: items.length ? items : undefined,
  }
  const local: Record<string, string> = {}
  if (!payload.title) local.title = '请填写会议标题'
  if (!payload.meetingDate) local.meetingDate = '请选择会议日期'
  if (!payload.host) local.host = '请填写主持人'
  if (Object.keys(local).length) {
    fieldErrors.value = local
    void focusFirstError()
    return
  }
  saving.value = true
  try {
    const created = await meetingApi.create(payload)
    toast.success(
      items.length ? `会议已创建，并录入 ${items.length} 条决议事项` : '会议已创建，可以开始录入决议事项',
    )
    creating.value = false
    void router.push(`/meetings/${created.id}`)
  } catch (err) {
    const apiError = toApiError(err)
    applyFieldErrors(apiError)
    void focusFirstError()
  } finally {
    saving.value = false
  }
}

/**
 * 422 的 fieldErrors 逐字段回填，未匹配到的落到顶部提示。
 * 决议行错误形如 `items[2].dueDate`（K 从 1 起），折算成 0 起的行下标后落到行内单元格。
 */
function applyFieldErrors(apiError: ApiError): void {
  const next: Record<string, string> = {}
  for (const field of apiError.fieldErrors ?? []) {
    const key = itemErrorKey(field.field, field.index)
    if (!next[key]) next[key] = field.message
  }
  fieldErrors.value = next
  const unmatched = Object.keys(next).length === 0
  formError.value = unmatched ? apiError.message : ''
}

function itemErrorKey(field: string, index?: number): string {
  const matched = /^items\[(\d+)\]\.(.+)$/.exec(field)
  if (matched) return `items:${Number(matched[1]) - 1}:${matched[2]}`
  // 整行级错误（后端 `items[K]` 不带字段）：落到 row 键，由 rowError 优先展示
  const rowOnly = /^items\[(\d+)\]$/.exec(field)
  if (rowOnly) return `items:${Number(rowOnly[1]) - 1}:row`
  if (typeof index === 'number') return `items:${index}:${field}`
  return field
}

async function load(): Promise<void> {
  const current = ++seq
  loading.value = true
  error.value = null
  try {
    const page = await meetingApi.list({
      archived: state.value.archived,
      keyword: state.value.q.trim() || undefined,
      page: state.value.page,
      size: size.value,
    })
    if (current !== seq) return
    rows.value = page.items
    total.value = page.total
  } catch (err) {
    if (current !== seq) return
    error.value = toApiError(err)
    rows.value = []
    total.value = 0
  } finally {
    if (current === seq) loading.value = false
  }
}

onMounted(async () => {
  void load()
  try {
    owners.value = (await metaApi.options()).owners
  } catch {
    /* 主持人/参会人选择依赖用户列表，失败不阻塞列表浏览 */
  }
})

watch(
  () => [state.value.q, state.value.archived, state.value.page],
  ([q], previous) => {
    const queryChanged = q !== (previous?.[0] ?? '')
    if (queryChanged) {
      clearTimeout(keywordTimer)
      keywordTimer = setTimeout(() => void load(), 260)
      return
    }
    void load()
  },
)

function onKeyword(value: string): void {
  state.value.q = value
  state.value.page = 1
}

function toggleArchived(value: boolean): void {
  state.value.archived = value
  state.value.page = 1
}

function onPage(page: number): void {
  state.value.page = page
}

function openDetail(row: MeetingRow): void {
  void router.push(`/meetings/${row.id}`)
}

function askArchive(row: MeetingRow): void {
  archiveTarget.value = row
}

async function confirmArchive(): Promise<void> {
  const target = archiveTarget.value
  if (!target) return
  archiving.value = true
  try {
    await meetingApi.archive(target.id)
    archiveTarget.value = null
    toast.success('会议已归档，其下任务与来源保留')
    void load()
  } catch (err) {
    toast.error(toApiError(err).message)
  } finally {
    archiving.value = false
  }
}

function closeBadge(row: MeetingRow): { text: string; tone: BadgeTone } {
  if (row.closed) return { text: '已闭环', tone: 'success' }
  if (row.taskCount > 0) return { text: `未完成 ${row.unfinishedCount} 条`, tone: 'warning' }
  return { text: '待派发', tone: 'neutral' }
}
</script>

<template>
  <section class="page">
    <PageHeader
      title="会议与决议"
      description="开会结论结构化录入，勾选决议事项一键转任务；会议只归档不删除。"
    >
      <template #actions>
        <AppButton variant="primary" @click="openCreate">新建会议</AppButton>
      </template>
    </PageHeader>

    <div class="toolbar">
      <AppInput
        :model-value="state.q"
        type="search"
        placeholder="搜索会议标题"
        size="compact"
        class="toolbar__search"
        @update:model-value="onKeyword"
      />
      <label class="switch">
        <input
          type="checkbox"
          class="switch__input"
          :checked="state.archived"
          @change="toggleArchived(($event.target as HTMLInputElement).checked)"
        />
        <span class="switch__track" aria-hidden="true"><span class="switch__dot" /></span>
        <span class="switch__label">显示已归档</span>
      </label>
    </div>

    <ErrorState
      v-if="error"
      title="会议列表加载失败"
      :description="error.message"
      :trace-id="error.traceId ?? undefined"
      @retry="load"
    />

    <template v-else>
      <DataTable
        v-if="rows.length || loading"
        :columns="columns"
        :rows="rows"
        row-key="id"
        :loading="loading"
        clickable
        empty-title="还没有会议"
        empty-hint="新建会议后，把开会结论逐条录成决议事项，再转成任务派到人。"
        @row-click="openDetail"
      >
        <template #cell-title="{ row }">
          <span class="row-title">{{ row.title }}</span>
          <AppBadge v-if="row.archived" tone="neutral" size="sm" class="row-archived">已归档</AppBadge>
        </template>
        <template #cell-meetingDate="{ row }"><span class="num">{{ formatDate(row.meetingDate) }}</span></template>
        <template #cell-host="{ row }">{{ row.host }}</template>
        <template #cell-taskCount="{ row }">
          <span class="num" :class="{ zero: row.taskCount === 0 }">{{ row.taskCount }}</span>
        </template>
        <template #cell-unfinishedCount="{ row }">
          <span class="num" :class="{ zero: row.unfinishedCount === 0 }">{{ row.unfinishedCount }}</span>
        </template>
        <template #cell-closed="{ row }">
          <AppBadge :tone="closeBadge(row).tone" size="sm">{{ closeBadge(row).text }}</AppBadge>
        </template>
        <template #cell-actions="{ row }">
          <div class="row-actions" @click.stop>
            <AppButton variant="ghost" size="compact" @click="openDetail(row)">查看</AppButton>
            <AppButton
              v-if="!row.archived"
              variant="ghost"
              size="compact"
              @click="askArchive(row)"
            >
              归档
            </AppButton>
          </div>
        </template>
      </DataTable>
      <div v-else class="empty">
        <EmptyState
          :title="state.archived ? '没有已归档的会议' : '还没有会议'"
          :description="
            state.archived ? '归档后的会议会出现在这里，其下任务与来源不受影响。' : '新建会议后，把开会结论逐条录成决议事项，再转成任务派到人。'
          "
        />
        <AppButton v-if="!state.archived" variant="primary" @click="openCreate">新建会议</AppButton>
      </div>

      <Pagination
        v-if="total > size"
        :page="state.page"
        :size="size"
        :total="total"
        @update:page="onPage"
      />
    </template>

    <ConfirmDialog
      :open="archiveTarget !== null"
      title="归档会议"
      description="归档后会议从列表隐藏，已生成的任务与来源信息不受影响。"
      confirm-label="归档会议"
      tone="danger"
      :loading="archiving"
      @confirm="confirmArchive"
      @close="archiveTarget = null"
    />

    <Modal
      :open="creating"
      title="新建会议"
      description="会议信息是决议事项的来源快照；决议事项可以现在录，也可以之后在详情页补。"
      size="lg"
      :close-on-overlay="!saving"
      @close="closeCreate"
    >
      <form class="form" @submit.prevent="submitCreate">
        <AppInput
          v-model="form.title"
          label="会议标题"
          placeholder="例如：十月项目推进会"
          required
          :invalid="!!fieldErrors.title"
          :error="fieldErrors.title"
        />
        <div class="form__row">
          <AppDatePicker
            v-model="form.meetingDate"
            label="会议日期"
            required
            :invalid="!!fieldErrors.meetingDate"
            :error="fieldErrors.meetingDate"
          />
          <AppSearchSelect
            :model-value="form.host"
            :options="hostOptions"
            label="主持人"
            placeholder="搜索或输入姓名"
            allow-custom
            required
            :invalid="!!fieldErrors.host"
            :error="fieldErrors.host"
            hint="不在系统里的人也能填，仅记录姓名，不参与提醒与权限"
            @update:model-value="form.host = $event"
            @change="onHostChange"
          />
        </div>
        <fieldset class="attendees">
          <legend class="attendees__legend">参会人</legend>
          <UserMultiSelect
            v-model="form.attendeeIds"
            :options="owners"
            placeholder="搜索并添加参会人"
            :invalid="!!fieldErrors.attendeeIds"
            :error="fieldErrors.attendeeIds"
          />
          <p class="form__hint">{{ activeOwners.length }} 位可选用户；选中主持人会自动带入一次</p>
        </fieldset>

        <section class="items">
          <header class="items__head">
            <div>
              <h3 class="items__title">决议事项（可留空）</h3>
              <p class="items__hint">每条决议会在转任务时生成一条任务；留空则稍后在会议详情里逐条录入。</p>
            </div>
            <AppButton variant="secondary" size="compact" type="button" @click="addDecision">+ 添加一条</AppButton>
          </header>

          <div v-if="!decisions.length" class="items__empty">本次会议暂不录入决议事项。</div>

          <article
            v-for="(row, index) in decisions"
            :key="row.key"
            class="item"
            :class="{ 'item--invalid': !!rowError(index) }"
          >
            <header class="item__head">
              <span class="item__no num">#{{ index + 1 }}</span>
              <button
                type="button"
                class="item__remove"
                :aria-label="`移除第 ${index + 1} 行决议`"
                @click="removeDecision(index)"
              >
                <svg width="14" height="14" viewBox="0 0 16 16" aria-hidden="true">
                  <path d="M4 4l8 8M12 4l-8 8" fill="none" stroke="currentColor" stroke-width="1.5" stroke-linecap="round" />
                </svg>
              </button>
            </header>
            <div :data-cell="`items:${index}:description`">
              <AppInput
                v-model="row.description"
                label="决议描述"
                placeholder="例如：下周完成灰度发布方案"
                required
                :maxlength="100"
                :invalid="!!itemError(index, 'description')"
                :error="itemError(index, 'description')"
              />
            </div>
            <div class="item__row">
              <div :data-cell="`items:${index}:ownerId`">
                <AppSelect
                  v-model="row.ownerId"
                  label="负责人"
                  placeholder="选择负责人"
                  :options="ownerOptions"
                  required
                  :invalid="!!itemError(index, 'ownerId')"
                  :error="itemError(index, 'ownerId')"
                />
              </div>
              <div :data-cell="`items:${index}:dueDate`">
                <AppDatePicker
                  v-model="row.dueDate"
                  label="截止日期"
                  required
                  :invalid="!!itemError(index, 'dueDate')"
                  :error="itemError(index, 'dueDate')"
                />
              </div>
            </div>
            <p v-if="rowError(index)" class="item__error" role="alert">{{ rowError(index) }}</p>
          </article>
        </section>

        <AppTextarea v-model="form.note" label="会议备注" :rows="2" :maxlength="200" placeholder="可选" />
        <p v-if="formError" class="form__error" role="alert" tabindex="-1" data-create-error>{{ formError }}</p>
        <div class="form__foot">
          <AppButton variant="secondary" :disabled="saving" @click="closeCreate">取消</AppButton>
          <AppButton type="submit" :loading="saving" loading-text="创建中">创建会议</AppButton>
        </div>
      </form>
    </Modal>
  </section>
</template>

<style scoped>
.page {
  display: flex;
  flex-direction: column;
  gap: var(--sp-5);
  padding: var(--sp-6);
}
.toolbar {
  display: flex;
  align-items: center;
  gap: var(--sp-4);
  flex-wrap: wrap;
}
.toolbar__search {
  width: 280px;
}
.switch {
  display: inline-flex;
  align-items: center;
  gap: var(--sp-2);
  font-size: var(--fs-body-sm);
  color: var(--c-ink-600);
  cursor: pointer;
}
.switch__input {
  position: absolute;
  opacity: 0;
  width: 1px;
  height: 1px;
}
.switch__track {
  width: 36px;
  height: 20px;
  border-radius: var(--r-pill);
  background: var(--c-line);
  position: relative;
  transition: background-color var(--t-toggle) var(--ease);
}
.switch__dot {
  position: absolute;
  top: 2px;
  left: 2px;
  width: 16px;
  height: 16px;
  border-radius: 50%;
  background: var(--c-surface);
  box-shadow: var(--sh-1);
  transition: transform var(--t-toggle) var(--ease);
}
.switch__input:checked + .switch__track {
  background: var(--c-primary);
}
.switch__input:checked + .switch__track .switch__dot {
  transform: translateX(16px);
}
.switch__input:focus-visible + .switch__track {
  outline: 2px solid var(--c-primary);
  outline-offset: 2px;
}
.row-title {
  font-weight: 500;
  color: var(--c-ink-900);
}
.row-archived {
  margin-left: var(--sp-2);
}
.row-actions {
  display: flex;
  justify-content: flex-end;
  gap: var(--sp-1);
}
.zero {
  color: var(--c-ink-400);
}
.empty {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: var(--sp-3);
  padding: var(--sp-8) var(--sp-6);
  border: 1px solid var(--c-line);
  border-radius: var(--r-lg);
  background: var(--c-surface);
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
.attendees__empty {
  margin: 0;
  font-size: var(--fs-caption);
  color: var(--c-ink-500);
}
.form__hint {
  margin: var(--sp-2) 0 0;
  font-size: var(--fs-caption);
  color: var(--c-ink-500);
}
.form__field {
  display: flex;
  flex-direction: column;
  gap: var(--sp-2);
}
/* -------------------------------------------------- 决议事项子卡（DESIGN §3.4） */
.items {
  display: flex;
  flex-direction: column;
  gap: var(--sp-3);
  padding: var(--sp-4);
  border: 1px solid var(--c-line);
  border-radius: var(--r-md);
  background: var(--c-bg);
}
.items__head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: var(--sp-3);
}
.items__title {
  margin: 0;
  font-size: var(--fs-body-sm);
  font-weight: 600;
  color: var(--c-ink-800);
}
.items__hint {
  margin: var(--sp-1) 0 0;
  font-size: var(--fs-caption);
  color: var(--c-ink-500);
}
.items__empty {
  padding: var(--sp-3) 0;
  font-size: var(--fs-body-sm);
  color: var(--c-ink-400);
}
.item {
  display: flex;
  flex-direction: column;
  gap: var(--sp-3);
  padding: var(--sp-3);
  border: 1px solid var(--c-line);
  border-radius: var(--r-md);
  background: var(--c-surface);
}
/* 行内出错：整卡加红边，配合字段红框，一眼看出是哪一行 */
.item--invalid {
  border-color: var(--c-danger-border);
}
.item__head {
  display: flex;
  align-items: center;
  justify-content: space-between;
}
.item__no {
  font-size: var(--fs-caption);
  font-weight: 600;
  color: var(--c-ink-400);
}
.item__remove {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 24px;
  height: 24px;
  padding: 0;
  border: 0;
  border-radius: var(--r-sm);
  background: transparent;
  color: var(--c-ink-400);
  cursor: pointer;
}
.item__remove:hover {
  background: var(--c-danger-soft);
  color: var(--c-danger-text);
}
.item__remove:focus-visible {
  outline: 2px solid var(--c-primary);
  outline-offset: 1px;
}
.item__row {
  display: grid;
  grid-template-columns: 220px 176px;
  gap: var(--sp-3);
}
.item__error {
  margin: 0;
  font-size: var(--fs-caption);
  color: var(--c-danger-text);
}
@media (max-width: 640px) {
  .item__row {
    grid-template-columns: 1fr;
  }
}
</style>
