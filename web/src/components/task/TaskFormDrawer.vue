<script setup lang="ts">
/**
 * 新建 / 编辑任务抽屉（DESIGN §4.3）。
 *
 * 三个硬要求：
 * 1. 来源两种形态互斥（会议决议 vs 说明），所以它们在同一组里切换而不是两个表单；
 * 2. 422 必须能定位：顶部摘要 + 首个错误项滚动聚焦 + 行内文案，服务端 fieldErrors 直接回填；
 * 3. 编辑态不改来源、不改负责人与截止日期 —— 后者要走 deta 页的「改派 / 改期」才留得下流转记录。
 */
import { computed, nextTick, reactive, ref, watch } from 'vue'
import Drawer from '@/components/ui/Drawer.vue'
import AppButton from '@/components/ui/AppButton.vue'
import AppInput from '@/components/ui/AppInput.vue'
import AppSelect from '@/components/ui/AppSelect.vue'
import AppDatePicker from '@/components/ui/AppDatePicker.vue'
import AppTextarea from '@/components/ui/AppTextarea.vue'
import { taskApi } from '@/api/tasks'
import { toApiError } from '@/api/http'
import { useToast } from '@/composables/useToast'
import { PRIORITY_LABELS, SOURCE_FORM_LABELS } from '@/types/enums'
import type { Priority, SourceForm, SourceType } from '@/types/enums'
import type { DecisionItemRow, SourceTypeOption, TaskDetail, UserOption } from '@/types/api'
import { sourceOptions as buildSourceOptions, type SourceOption } from '@/utils/sourceOptions'

const props = withDefaults(
  defineProps<{
    open: boolean
    task?: TaskDetail | null
    owners: UserOption[]
    meetings: Array<{ id: number; title: string }>
    /** meta.sourceTypes 全量（AC-2 / AC-5）：来源选项与形态都来自配置，不写死三类 */
    sourceTypes?: SourceTypeOption[]
  }>(),
  { task: null, sourceTypes: () => [] },
)

const emit = defineEmits<{ close: []; saved: [detail: TaskDetail] }>()

const toast = useToast()

const PRIORITIES: Priority[] = ['HIGH', 'MEDIUM', 'LOW']
const TITLE_MAX = 100
const NOTE_MAX = 200

interface FormState {
  title: string
  ownerId: number | ''
  dueDate: string
  sourceType: SourceType
  meetingId: number | ''
  decisionItemId: number | ''
  note: string
  priority: Priority
}

const form = reactive<FormState>(blankForm())
const errors = reactive<Record<string, string>>({})
const summary = ref('')
const saving = ref(false)
const items = ref<DecisionItemRow[]>([])
const itemsLoading = ref(false)
/** 选中决议项时自动带出的标题，用户改过之后就不再覆盖 */
const autoTitle = ref('')
/** 本次会话里是否切换过形态：切换后给一行可见提示，保存前不再弹确认框 */
const formSwitched = ref(false)

const isEdit = computed(() => Boolean(props.task?.id))
const drawerTitle = computed(() => (isEdit.value ? '编辑任务' : '新建任务'))

/** 建任务只列启用来源，按 sort（AC-2）：停用项不得出现在新建下拉里；文案=名称，不露编码 */
const sourceOptions = computed<SourceOption[]>(() =>
  buildSourceOptions(props.sourceTypes, { allOption: false, includeDisabled: false }),
)

/** 编辑态只读展示当前来源：名称与停用标注都取配置，配置缺该编码时回落到任务行快照 */
const currentSource = computed<SourceTypeOption | undefined>(() =>
  (Array.isArray(props.sourceTypes) ? props.sourceTypes : []).find(
    (item) => item.value === form.sourceType,
  ),
)
const currentSourceLabel = computed(() => {
  const name = currentSource.value?.label || props.task?.source?.typeLabel || form.sourceType
  return currentSource.value && currentSource.value.enabled === false ? `${name}（已停用）` : name
})

/**
 * 形态取配置里该编码的 form（AC-2）：MEETING 才显示会议/决议项，NOTE 显示说明。
 * 配置未加载时按编码兜底，避免首帧把会议字段藏起来。
 */
function formOf(code: SourceType): SourceForm {
  const picked = props.sourceTypes.find((item) => item.value === code)
  if (picked) return picked.form
  return code === 'MEETING' ? 'MEETING' : 'NOTE'
}

const activeForm = computed<SourceForm>(() => formOf(form.sourceType))

const ownerOptions = computed(() =>
  (Array.isArray(props.owners) ? props.owners : []).map((owner) => ({
    label: owner.name,
    value: owner.id,
  })),
)

const meetingOptions = computed(() =>
  (Array.isArray(props.meetings) ? props.meetings : []).map((meeting) => ({
    label: meeting.title,
    value: meeting.id,
  })),
)

const itemOptions = computed(() =>
  items.value.map((item) => ({
    label: `${item.description} · ${item.owner?.name ?? '未指派'}`,
    value: item.id,
    disabled: Boolean(item.converted),
  })),
)

const priorityOptions = PRIORITIES.map((priority) => ({
  label: PRIORITY_LABELS[priority],
  value: priority,
}))

function blankForm(): FormState {
  return {
    title: '',
    ownerId: '',
    dueDate: '',
    sourceType: 'MEETING',
    meetingId: '',
    decisionItemId: '',
    note: '',
    priority: 'MEDIUM',
  }
}

/** 仅用于「已过期」提示，不参与任何逾期判定（逾期一律看服务端字段）；时区与后端一致 */
function todayIso(): string {
  return new Intl.DateTimeFormat('en-CA', { timeZone: 'Asia/Shanghai' }).format(new Date())
}

/** 仅用于「已过期」提示，不参与任何逾期判定（逾期一律看服务端字段） */
const dueDateInPast = computed(() => Boolean(form.dueDate) && form.dueDate < todayIso())

watch(
  () => props.open,
  (open) => {
    if (!open) return
    resetForm()
  },
  { immediate: true },
)

watch(
  () => form.meetingId,
  async (meetingId, previous) => {
    if (meetingId === previous) return
    form.decisionItemId = ''
    items.value = []
    if (formOf(form.sourceType) !== 'MEETING' || meetingId === '') return
    itemsLoading.value = true
    try {
      items.value = await taskApi.decisionItems(Number(meetingId))
    } catch (error) {
      const apiError = toApiError(error)
      toast.warning(apiError.message || '决议项加载失败，可以先只选择会议')
    } finally {
      itemsLoading.value = false
    }
  },
)

function resetForm(): void {
  const task = props.task
  Object.assign(form, blankForm())
  Object.keys(errors).forEach((key) => delete errors[key])
  summary.value = ''
  autoTitle.value = ''
  items.value = []
  formSwitched.value = false
  if (!task) return
  form.title = task.title
  form.ownerId = task.owner?.id ?? ''
  form.dueDate = task.dueDate ?? ''
  form.sourceType = task.source?.type ?? 'MEETING'
  form.meetingId = task.source?.meetingId ?? ''
  form.decisionItemId = task.source?.decisionItemId ?? ''
  form.note = task.source?.note ?? ''
  form.priority = task.priority ?? 'MEDIUM'
  if (activeForm.value === 'MEETING' && form.meetingId !== '') {
    void loadItems(Number(form.meetingId))
  }
}

async function loadItems(meetingId: number): Promise<void> {
  itemsLoading.value = true
  try {
    items.value = await taskApi.decisionItems(meetingId)
  } catch {
    items.value = []
  } finally {
    itemsLoading.value = false
  }
}

function onDecisionItem(value: string): void {
  form.decisionItemId = value ? Number(value) : ''
  const picked = items.value.find((item) => item.id === form.decisionItemId)
  if (!picked) return
  if (!form.title.trim() || form.title === autoTitle.value) {
    form.title = picked.description
    autoTitle.value = picked.description
  }
  if (!form.ownerId && picked.owner?.id) form.ownerId = picked.owner.id
  if (!form.dueDate && picked.dueDate) form.dueDate = picked.dueDate
  clearError('title')
  clearError('ownerId')
  clearError('dueDate')
}

function onSourceChange(value: string): void {
  if (isEdit.value || !value || value === form.sourceType) return
  const nextForm = formOf(value)
  const changedForm = nextForm !== activeForm.value
  form.sourceType = value
  clearError('meetingId')
  clearError('note')
  // 切形态即清空另一组字段：避免带着旧形态的会议/说明提交，被服务端 422 打回（AC-2）
  if (nextForm !== 'MEETING') {
    form.meetingId = ''
    form.decisionItemId = ''
    items.value = []
  } else {
    form.note = ''
  }
  formSwitched.value = changedForm
}

function clearError(key: string): void {
  if (errors[key]) delete errors[key]
  if (!Object.keys(errors).length) summary.value = ''
}

const fieldEls: Record<string, HTMLElement | null> = {}

function setFieldEl(key: string, el: unknown): void {
  if (!el) {
    fieldEls[key] = null
    return
  }
  const element = el instanceof HTMLElement ? el : (el as { $el?: HTMLElement }).$el
  fieldEls[key] = element instanceof HTMLElement ? element : null
}

function validate(): string[] {
  const order: string[] = []
  errors.title = form.title.trim() ? '' : '请填写任务标题'
  if (form.title.trim().length > TITLE_MAX) errors.title = `标题不超过 ${TITLE_MAX} 字`
  if (!form.title.trim()) order.push('title')
  else if (form.title.trim().length > TITLE_MAX) order.push('title')

  if (form.ownerId === '') {
    errors.ownerId = '请选择负责人'
    order.push('ownerId')
  }
  if (!form.dueDate) {
    errors.dueDate = '请选择截止日期'
    order.push('dueDate')
  }
  // 编辑态来源只读（D6）：来源字段不参与校验，也不回写；只校验可改的标题与优先级
  if (!isEdit.value) {
    if (activeForm.value === 'MEETING') {
      if (form.meetingId === '') {
        errors.meetingId = '请选择来源会议'
        order.push('meetingId')
      }
    } else if (!form.note.trim()) {
      errors.note = '请填写来源说明，说明这条任务从哪来'
      order.push('note')
    } else if (form.note.trim().length > NOTE_MAX) {
      errors.note = `来源说明不超过 ${NOTE_MAX} 字`
      order.push('note')
    }
  }
  Object.keys(errors).forEach((key) => {
    if (!errors[key]) delete errors[key]
  })
  return order
}

async function focusFirst(order: string[]): Promise<void> {
  const first = order[0]
  if (!first) return
  await nextTick()
  const el = fieldEls[first]
  if (!el) return
  el.scrollIntoView({ block: 'center', behavior: 'smooth' })
  const target = el.querySelector<HTMLElement>('input, select, textarea')
  target?.focus()
}

const SERVER_FIELD_MAP: Record<string, string> = {
  sourceMeetingId: 'meetingId',
  sourceDecisionItemId: 'decisionItemId',
  sourceNote: 'note',
}

function applyServerErrors(fieldErrors: Array<{ field: string; message: string }>): void {
  const order: string[] = []
  for (const item of fieldErrors) {
    const key = SERVER_FIELD_MAP[item.field] ?? item.field
    if (!(key in form)) continue
    errors[key] = item.message
    order.push(key)
  }
  if (order.length) {
    summary.value = `还有 ${order.length} 项需要修改`
    void focusFirst(order)
  }
}

async function save(): Promise<void> {
  if (saving.value) return
  summary.value = ''
  const order = validate()
  if (order.length) {
    summary.value = `还有 ${order.length} 项需要填写`
    void focusFirst(order)
    return
  }
  saving.value = true
  try {
    const detail = props.task
      ? await taskApi.update(props.task.id, {
          title: form.title.trim(),
          priority: form.priority,
          // 来源不可改（D6）：编辑态不提交任何 source* 字段，服务端也不会接受
        })
      : await taskApi.create({
          title: form.title.trim(),
          ownerId: form.ownerId === '' ? null : Number(form.ownerId),
          dueDate: form.dueDate,
          sourceType: form.sourceType,
          sourceMeetingId: activeForm.value === 'MEETING' ? Number(form.meetingId) : undefined,
          sourceDecisionItemId:
            activeForm.value === 'MEETING' && form.decisionItemId !== ''
              ? Number(form.decisionItemId)
              : undefined,
          sourceNote: activeForm.value === 'MEETING' ? form.note.trim() || undefined : form.note.trim(),
          priority: form.priority,
        })
    toast.success(isEdit.value ? '任务已更新' : '任务已创建')
    emit('saved', detail)
    emit('close')
  } catch (error) {
    const apiError = toApiError(error)
    if (apiError.status === 422 && apiError.fieldErrors.length) {
      applyServerErrors(apiError.fieldErrors)
    } else if (apiError.status === 409) {
      summary.value = apiError.message || '任务状态已变化，请关闭抽屉后重试'
    } else {
      summary.value = apiError.message || '保存失败，请稍后重试'
    }
  } finally {
    saving.value = false
  }
}
</script>

<template>
  <Drawer
    :open="open"
    :title="drawerTitle"
    :description="isEdit ? '来源不可修改；改负责人或截止日期请用「改派 / 改期」' : '四个必填项齐全才会保存'"
    :width="560"
    @close="emit('close')"
  >
    <form class="task-form" novalidate @submit.prevent="save">
      <div v-if="summary" class="task-form__summary" role="alert">
        {{ summary }}
      </div>

      <section class="task-form__group">
        <h3 class="task-form__legend">任务信息</h3>
        <div :ref="(el) => setFieldEl('title', el)">
          <AppInput
            :model-value="form.title"
            label="任务标题"
            required
            :maxlength="TITLE_MAX"
            placeholder="一句话说清要交付什么"
            :invalid="Boolean(errors.title)"
            :error="errors.title"
            @update:model-value="form.title = $event; clearError('title')"
          />
        </div>
        <p class="task-form__hint">
          <span class="task-form__hint-key">状态</span>
          {{ isEdit ? '当前状态见详情页，流转请用详情页动作' : '新建后状态：待开始' }}
        </p>
      </section>

      <section class="task-form__group">
        <h3 class="task-form__legend">来源</h3>
        <div v-if="isEdit" class="source-readonly">
          <span class="source-readonly__label">来源类型</span>
          <span class="source-readonly__value">{{ currentSourceLabel }}</span>
        </div>
        <AppSelect
          v-else
          :model-value="form.sourceType"
          :options="sourceOptions"
          label="来源类型"
          required
          :hint="`当前形态：${SOURCE_FORM_LABELS[activeForm]}`"
          @update:model-value="onSourceChange"
        />
        <p v-if="isEdit" class="task-form__hint">
          来源在创建时确定，不可修改；如需更换来源请新建任务
        </p>
        <p v-if="formSwitched && !isEdit" class="task-form__warn">
          已切换形态，保存后按新形态校验
        </p>

        <template v-if="activeForm === 'MEETING'">
          <div :ref="(el) => setFieldEl('meetingId', el)">
            <AppSelect
              :model-value="form.meetingId"
              :options="meetingOptions"
              label="来源会议"
              required
              :disabled="isEdit"
              placeholder="选择会议"
              :invalid="Boolean(errors.meetingId)"
              :error="errors.meetingId"
              @update:model-value="form.meetingId = $event ? Number($event) : ''; clearError('meetingId')"
            />
          </div>
          <AppSelect
            :model-value="form.decisionItemId"
            :options="itemOptions"
            label="关联决议项"
            :disabled="isEdit || form.meetingId === '' || itemsLoading"
            :placeholder="itemsLoading ? '决议项加载中…' : '可不选，只关联会议'"
            hint="已转为任务的决议项不可重复选择"
            @update:model-value="onDecisionItem"
          />
        </template>
        <div v-else :ref="(el) => setFieldEl('note', el)">
          <AppTextarea
            :model-value="form.note"
            label="来源说明"
            required
            :rows="3"
            :maxlength="NOTE_MAX"
            :disabled="isEdit"
            placeholder="例如：项目周会临时追加，或某条日常待办的出处"
            :invalid="Boolean(errors.note)"
            :error="errors.note"
            @update:model-value="form.note = $event; clearError('note')"
          />
        </div>
      </section>

      <section class="task-form__group">
        <h3 class="task-form__legend">指派与期限</h3>
        <div :ref="(el) => setFieldEl('ownerId', el)">
          <AppSelect
            :model-value="form.ownerId"
            :options="ownerOptions"
            label="负责人"
            required
            :disabled="isEdit"
            placeholder="选择负责人"
            :invalid="Boolean(errors.ownerId)"
            :error="errors.ownerId"
            @update:model-value="form.ownerId = $event ? Number($event) : ''; clearError('ownerId')"
          />
        </div>
        <div :ref="(el) => setFieldEl('dueDate', el)">
          <AppDatePicker
            :model-value="form.dueDate"
            label="截止日期"
            required
            :disabled="isEdit"
            :invalid="Boolean(errors.dueDate)"
            :error="errors.dueDate"
            @update:model-value="form.dueDate = $event; clearError('dueDate')"
          />
        </div>
        <p v-if="dueDateInPast && !isEdit" class="task-form__warn">
          该截止日期已过期，保存后将立即显示逾期
        </p>
      </section>

      <section class="task-form__group">
        <h3 class="task-form__legend">优先级</h3>
        <div class="segmented segmented--compact" role="radiogroup" aria-label="优先级">
          <button
            v-for="option in priorityOptions"
            :key="option.value"
            type="button"
            role="radio"
            class="segmented__item"
            :class="{ 'is-active': form.priority === option.value }"
            :aria-checked="form.priority === option.value"
            @click="form.priority = option.value as Priority"
          >
            {{ option.label }}
          </button>
        </div>
      </section>
    </form>

    <template #footer>
      <AppButton variant="secondary" :disabled="saving" @click="emit('close')">取消</AppButton>
      <AppButton variant="primary" size="large" :loading="saving" loading-text="保存中" @click="save">
        保存
      </AppButton>
    </template>
  </Drawer>
</template>

<style scoped>
.task-form {
  display: flex;
  flex-direction: column;
  gap: var(--sp-6);
}
.task-form__summary {
  padding: var(--sp-3);
  border: 1px solid var(--c-danger-border);
  border-radius: var(--r-md);
  background: var(--c-danger-soft);
  color: var(--c-danger-text);
  font-size: var(--fs-body-sm);
}
.task-form__group {
  display: flex;
  flex-direction: column;
  gap: var(--sp-3);
}
.task-form__legend {
  margin: 0;
  font-size: var(--fs-caption);
  font-weight: 600;
  letter-spacing: 0.04em;
  color: var(--c-ink-500);
  text-transform: uppercase;
}
.task-form__hint {
  margin: 0;
  font-size: var(--fs-caption);
  color: var(--c-ink-500);
}
.task-form__hint-key {
  display: inline-block;
  margin-right: var(--sp-2);
  color: var(--c-ink-400);
}
/* 编辑态来源只读（D6）：与禁用输入同高同圆角，肉眼可辨“此处不可改” */
.source-readonly {
  display: flex;
  align-items: center;
  gap: var(--sp-3);
  height: 32px;
  padding: 0 var(--sp-3);
  border: 1px solid var(--c-line);
  border-radius: var(--r-md);
  background: var(--c-line-soft);
}
.source-readonly__label {
  flex: none;
  font-size: var(--fs-body-sm);
  color: var(--c-ink-500);
}
.source-readonly__value {
  overflow: hidden;
  color: var(--c-ink-900);
  font-size: var(--fs-body-sm);
  font-weight: 500;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.task-form__warn {
  margin: 0;
  padding: var(--sp-2) var(--sp-3);
  border-radius: var(--r-sm);
  background: var(--c-warn-soft);
  border: 1px solid var(--c-warn-border);
  color: var(--c-warn);
  font-size: var(--fs-caption);
}
.segmented {
  display: inline-flex;
  padding: 2px;
  border: 1px solid var(--c-line);
  border-radius: var(--r-md);
  background: var(--c-surface-hover);
  align-self: flex-start;
}
.segmented__item {
  height: 32px;
  padding: 0 var(--sp-4);
  border: 0;
  border-radius: var(--r-sm);
  background: transparent;
  color: var(--c-ink-600);
  font-size: var(--fs-body-sm);
  cursor: pointer;
  transition: background var(--t-toggle) var(--ease), color var(--t-toggle) var(--ease);
}
.segmented__item:hover:not(:disabled):not(.is-active) {
  color: var(--c-ink-900);
}
.segmented__item.is-active {
  background: var(--c-surface);
  color: var(--c-primary);
  font-weight: 600;
  box-shadow: var(--sh-1);
}
.segmented__item:disabled {
  cursor: not-allowed;
  color: var(--c-ink-400);
}
.segmented__item:disabled.is-active {
  color: var(--c-primary);
  opacity: 0.7;
}
.segmented--compact .segmented__item {
  height: 28px;
  padding: 0 var(--sp-3);
}
</style>
