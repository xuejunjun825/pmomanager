<script setup lang="ts">
/**
 * 会议列表（§4.6）。会议只归档不删除：归档后从默认视图隐藏，但其下任务与来源快照不受影响（AC-5）。
 * 接口的 archived 是精确过滤而非「包含已归档」，所以开关切换的是「未归档视图 / 已归档视图」。
 */
import { computed, onMounted, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import PageHeader from '@/components/ui/PageHeader.vue'
import AppButton from '@/components/ui/AppButton.vue'
import AppInput from '@/components/ui/AppInput.vue'
import AppDatePicker from '@/components/ui/AppDatePicker.vue'
import AppTextarea from '@/components/ui/AppTextarea.vue'
import AppBadge from '@/components/ui/AppBadge.vue'
import DataTable from '@/components/ui/DataTable.vue'
import ConfirmDialog from '@/components/ui/ConfirmDialog.vue'
import Modal from '@/components/ui/Modal.vue'
import Pagination from '@/components/ui/Pagination.vue'
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

const creating = ref(false)
const saving = ref(false)
const formError = ref('')
const fieldErrors = ref<Record<string, string>>({})
const form = ref({ title: '', meetingDate: '', host: '', note: '', attendeeIds: [] as number[] })

const activeOwners = computed(() => owners.value.filter((user) => user.status === 'ACTIVE'))

function openCreate(): void {
  form.value = { title: '', meetingDate: '', host: '', note: '', attendeeIds: [] }
  fieldErrors.value = {}
  formError.value = ''
  creating.value = true
}

function closeCreate(): void {
  if (saving.value) return
  creating.value = false
}

function toggleAttendee(id: number): void {
  const list = form.value.attendeeIds
  const index = list.indexOf(id)
  if (index >= 0) list.splice(index, 1)
  else list.push(id)
}

async function submitCreate(): Promise<void> {
  fieldErrors.value = {}
  formError.value = ''
  const payload = {
    title: form.value.title.trim(),
    meetingDate: form.value.meetingDate,
    host: form.value.host.trim(),
    note: form.value.note.trim() || undefined,
    attendeeIds: [...form.value.attendeeIds],
  }
  const local: Record<string, string> = {}
  if (!payload.title) local.title = '请填写会议标题'
  if (!payload.meetingDate) local.meetingDate = '请选择会议日期'
  if (!payload.host) local.host = '请填写主持人'
  if (Object.keys(local).length) {
    fieldErrors.value = local
    return
  }
  saving.value = true
  try {
    const created = await meetingApi.create(payload)
    toast.success('会议已创建，可以开始录入决议事项')
    creating.value = false
    void router.push(`/meetings/${created.id}`)
  } catch (err) {
    const apiError = toApiError(err)
    applyFieldErrors(apiError)
  } finally {
    saving.value = false
  }
}

/** 422 的 fieldErrors 逐字段回填，未匹配到的落到顶部提示 */
function applyFieldErrors(apiError: ApiError): void {
  const next: Record<string, string> = {}
  for (const field of apiError.fieldErrors ?? []) {
    if (!next[field.field]) next[field.field] = field.message
  }
  fieldErrors.value = next
  const unmatched = Object.keys(next).length === 0
  formError.value = unmatched ? apiError.message : ''
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

    <Modal :open="creating" title="新建会议" description="会议信息是决议事项的来源快照。" size="md" @close="closeCreate">
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
          <AppInput
            v-model="form.host"
            label="主持人"
            placeholder="例如：张伟"
            required
            :invalid="!!fieldErrors.host"
            :error="fieldErrors.host"
          />
        </div>
        <fieldset class="attendees">
          <legend class="attendees__legend">参会人</legend>
          <div class="attendees__list">
            <label v-for="user in activeOwners" :key="user.id" class="attendees__item">
              <input
                type="checkbox"
                :checked="form.attendeeIds.includes(user.id)"
                @change="toggleAttendee(user.id)"
              />
              <span>{{ user.name }}</span>
            </label>
            <p v-if="!activeOwners.length" class="attendees__empty">暂无可选用户</p>
          </div>
        </fieldset>
        <AppTextarea v-model="form.note" label="会议备注" :rows="2" :maxlength="200" placeholder="可选" />
        <p v-if="formError" class="form__error" role="alert">{{ formError }}</p>
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
.attendees__list {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: var(--sp-2) var(--sp-3);
  max-height: 140px;
  overflow-y: auto;
  padding: var(--sp-2) 0;
}
.attendees__item {
  display: inline-flex;
  align-items: center;
  gap: var(--sp-2);
  font-size: var(--fs-body-sm);
  color: var(--c-ink-700);
  cursor: pointer;
}
.attendees__empty {
  margin: 0;
  font-size: var(--fs-caption);
  color: var(--c-ink-500);
}
</style>
