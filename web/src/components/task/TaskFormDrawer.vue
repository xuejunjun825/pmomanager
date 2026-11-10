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
import AppSearchSelect from '@/components/ui/AppSearchSelect.vue'
import AppSelect from '@/components/ui/AppSelect.vue'
import AppDatePicker from '@/components/ui/AppDatePicker.vue'
import AppTextarea from '@/components/ui/AppTextarea.vue'
import { taskApi } from '@/api/tasks'
import { toApiError } from '@/api/http'
import { useToast } from '@/composables/useToast'
import { PRIORITY_LABELS, SOURCE_FORM_LABELS } from '@/types/enums'
import type { Priority, SourceForm, SourceType } from '@/types/enums'
import type {
  DecisionItemRow,
  ProjectOptionRow,
  SourceTypeOption,
  TaskDetail,
  UserOption,
} from '@/types/api'
import { sourceOptions as buildSourceOptions, type SourceOption } from '@/utils/sourceOptions'
import {
  DISABLED_SUFFIX,
  projectOptions as buildProjectOptions,
  projectView,
} from '@/utils/projectOptions'

const props = withDefaults(
  defineProps<{
    open: boolean
    task?: TaskDetail | null
    owners: UserOption[]
    meetings: Array<{ id: number; title: string }>
    /** meta.sourceTypes 全量（AC-2 / AC-5）：来源选项与形态都来自配置，不写死三类 */
    sourceTypes?: SourceTypeOption[]
    /** meta.projects 全量（含已停用）；新建只列启用项，编辑态用快照回显 */
    projects?: ProjectOptionRow[]
  }>(),
  { task: null, sourceTypes: () => [], projects: () => [] },
)

const emit = defineEmits<{
  close: []
  saved: [detail: TaskDetail]
  /** 项目清单可能过期（新增/停用/删除），请求父级重拉 meta（DESIGN §3.4） */
  'refresh-meta': []
}>()

const toast = useToast()

const PRIORITIES: Priority[] = ['HIGH', 'MEDIUM', 'LOW']
const TITLE_MAX = 100
const NOTE_MAX = 200

/** 决议项必填的四条原因文案：只由 decisionReason() 产出，避免校验、行内错误、摘要三处漂移 */
const DECISION_REQUIRED = '请选择关联决议项'
const DECISION_LOADING = '决议项加载中，请稍候'
const DECISION_LOAD_FAILED = '决议项加载失败，请点击下方「重试加载决议项」'
const DECISION_NONE_AVAILABLE =
  '该会议暂无可关联的决议项（均已成任务），请改选会议或改用其他来源'

interface FormState {
  title: string
  ownerId: number | ''
  dueDate: string
  sourceType: SourceType
  meetingId: number | ''
  decisionItemId: number | ''
  /** 项目型来源（form=PROJECT）的绑定位；其他形态恒为 '' */
  projectId: number | ''
  note: string
  priority: Priority
}

const form = reactive<FormState>(blankForm())
const errors = reactive<Record<string, string>>({})
const summary = ref('')
const saving = ref(false)
const items = ref<DecisionItemRow[]>([])
const itemsLoading = ref(false)
/** 决议项是否已成功加载过（区分「还没请求」与「请求回来是空/全已转任务」） */
const itemsLoaded = ref(false)
const itemsError = ref('')
/** 决议项请求序号：快速切会议时丢弃过期响应，避免 A 的列表顶掉 B 的选项 */
let itemsRequestSeq = 0
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
  // 配置缺该编码（来源已删）时用任务行快照的 form，否则项目型任务会被误判成说明型
  const snapshot = props.task?.source
  if (snapshot?.form && snapshot.type === code) return snapshot.form
  return code === 'MEETING' ? 'MEETING' : 'NOTE'
}

const activeForm = computed<SourceForm>(() => formOf(form.sourceType))

/** 新建下拉只列启用项目（AC-3）：停用项不得出现在建任务的可选项里，也不带「未指定项目」 */
const projectItems = computed(() =>
  buildProjectOptions(props.projects, { allOption: false, includeDisabled: false }),
)
/** 无可用项目：字段保留但禁用（不隐藏，避免表单项跳动，DESIGN §1.4） */
const noProjectAvailable = computed(() => projectItems.value.length === 0)
const NO_PROJECT_TEXT = '还没有可用项目。请联系管理员在「系统设置 → 项目配置」添加项目。'
const NO_MATCH_TEXT = '没有匹配的项目'

/** 编辑态只读回显项目：唯一判定点 `projectView`，停用加后缀、改形历史显示「未指定项目」 */
const currentProjectLabel = computed(() => {
  const view = projectView(props.task?.source)
  if (!view) return ''
  if (view.unspecified) return '未指定项目'
  const name = view.name || `项目 #${props.task?.source?.projectId}`
  return view.enabled ? name : `${name}${DISABLED_SUFFIX}`
})

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

/** 会议下没有任何可绑决议项（空 / 全部已成任务）：控件禁用并把原因说清楚，而不是静默放过 */
const noSelectableItems = computed(
  () =>
    itemsLoaded.value &&
    !itemsLoading.value &&
    !itemsError.value &&
    items.value.every((item) => item.converted),
)

/** 编辑态只读：会议名取下拉清单，配置缺该会议时回落到任务行快照，再兜底编号 */
const currentMeetingLabel = computed(() => {
  const id = props.task?.source?.meetingId
  if (id == null) return ''
  return props.meetings.find((meeting) => meeting.id === id)?.title || props.task?.source?.meetingTitle || `会议 #${id}`
})

/** 编辑态只读：决议项描述取已加载清单，列表为空/失败时兜底编号，永不空白（AC-5） */
const currentDecisionLabel = computed(() => {
  const id = props.task?.source?.decisionItemId
  if (id == null) return ''
  const found = items.value.find((item) => item.id === id)
  if (found) return found.converted ? `${found.description}（已转任务）` : found.description
  return itemsLoading.value ? '加载中…' : `决议项 #${id}`
})

/** 占位文案随状态变化，任何一态都不暗示决议项可以跳过（AC-7） */
const decisionPlaceholder = computed(() => {
  if (form.meetingId === '') return '请先选择来源会议'
  if (itemsLoading.value) return '决议项加载中…'
  if (itemsError.value) return '决议项加载失败'
  if (noSelectableItems.value) return '暂无可关联的决议项'
  return '选择决议项'
})

/** 行内错误：服务端 422 文案优先，其余由 decisionReason() 单点产出（AC-4 / AC-6） */
const decisionItemError = computed(() => errors.decisionItemId || decisionReason())

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
    projectId: '',
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
  (meetingId, previous) => {
    // 编辑态来源只读：resetForm 的回填会触发本 watcher，必须早退，
    // 否则异步清空刚回显的 decisionItemId，并重复请求决议项（AC-5）
    if (isEdit.value) return
    if (meetingId === previous) return
    form.decisionItemId = ''
    clearError('decisionItemId')
    items.value = []
    itemsLoaded.value = false
    itemsError.value = ''
    if (formOf(form.sourceType) !== 'MEETING' || meetingId === '') {
      itemsRequestSeq++
      itemsLoading.value = false
      return
    }
    void loadItems(Number(meetingId))
  },
)

function resetForm(): void {
  const task = props.task
  Object.assign(form, blankForm())
  Object.keys(errors).forEach((key) => delete errors[key])
  summary.value = ''
  autoTitle.value = ''
  items.value = []
  itemsLoaded.value = false
  itemsError.value = ''
  // 作废上一次打开时还在途的请求，避免它的响应落到这一次的回显上
  itemsRequestSeq++
  formSwitched.value = false
  if (!task) return
  form.title = task.title
  form.ownerId = task.owner?.id ?? ''
  form.dueDate = task.dueDate ?? ''
  form.sourceType = task.source?.type ?? 'MEETING'
  form.meetingId = task.source?.meetingId ?? ''
  form.decisionItemId = task.source?.decisionItemId ?? ''
  form.projectId = task.source?.projectId ?? ''
  form.note = task.source?.note ?? ''
  form.priority = task.priority ?? 'MEDIUM'
  // 编辑态只读行需要决议项描述：与新建共用同一个加载入口（不再写第二个 fetch）
  if (activeForm.value === 'MEETING' && form.meetingId !== '') {
    void loadItems(Number(form.meetingId))
  }
}

/** 决议项唯一加载入口：请求序号保证快速切会议时只有最后一次响应生效 */
async function loadItems(meetingId: number): Promise<void> {
  const requestId = ++itemsRequestSeq
  itemsLoading.value = true
  itemsError.value = ''
  itemsLoaded.value = false
  try {
    const rows = await taskApi.decisionItems(meetingId)
    if (requestId !== itemsRequestSeq) return
    items.value = rows
    itemsLoaded.value = true
  } catch {
    if (requestId !== itemsRequestSeq) return
    items.value = []
    itemsError.value = DECISION_LOAD_FAILED
  } finally {
    if (requestId === itemsRequestSeq) {
      itemsLoading.value = false
      // 加载结束就撤掉「加载中」这条陈旧提示，让行内回落到当前状态
      clearStaleLoadingError()
    }
  }
}

function clearStaleLoadingError(): void {
  if (errors.decisionItemId !== DECISION_LOADING) return
  delete errors.decisionItemId
  if (!Object.keys(errors).length) summary.value = ''
}

/** 加载失败后的唯一重试入口（AC-6 ②）：重试期间行内先显示「加载中」 */
function retryItems(): void {
  if (form.meetingId === '') return
  clearError('decisionItemId')
  void loadItems(Number(form.meetingId))
}

/** 未选决议项的原因文案：唯一的产出点，validate()、行内 :error、摘要共用同一判断 */
function decisionReason(): string {
  if (isEdit.value || form.meetingId === '') return ''
  if (form.decisionItemId !== '') return ''
  if (itemsLoading.value) return DECISION_LOADING
  if (itemsError.value) return DECISION_LOAD_FAILED
  if (noSelectableItems.value) return DECISION_NONE_AVAILABLE
  return DECISION_REQUIRED
}

function onDecisionItem(value: string): void {
  form.decisionItemId = value ? Number(value) : ''
  clearError('decisionItemId')
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
  clearError('decisionItemId')
  clearError('note')
  clearError('projectId')
  itemsLoaded.value = false
  itemsError.value = ''
  // 切形态即清空另外两组字段：三态互斥由服务端单点判定，前端不能带着旧字段提交（AC-2）
  if (nextForm !== 'MEETING') {
    form.meetingId = ''
    form.decisionItemId = ''
    items.value = []
  }
  if (nextForm !== 'PROJECT') form.projectId = ''
  if (nextForm !== 'NOTE') form.note = ''
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
      } else {
        // 会议与决议项共用一个 order 位：摘要计数「还有 1 项需要填写」与行内文案一致
        const reason = decisionReason()
        if (reason) {
          errors.decisionItemId = reason
          order.push('decisionItemId')
        }
      }
    } else if (activeForm.value === 'PROJECT') {
      // 项目型来源必须绑项目（AC-3）：三态互斥后会议/说明都不再是替代品
      if (form.projectId === '') {
        errors.projectId = '请选择来源项目'
        order.push('projectId')
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
  // jsdom / 老内核可能没有 scrollIntoView：滚动只是体验，缺了不能让错误提示丢失
  el.scrollIntoView?.({ block: 'center', behavior: 'smooth' })
  // 优先落到可交互控件（决议项 select 禁用时退到重试按钮），再退到 tabindex="-1" 容器，
  // 保证焦点一定停在这个字段上，不会跳回页面顶部
  const target =
    el.querySelector<HTMLElement>(
      'input:not([disabled]), select:not([disabled]), textarea:not([disabled]), button:not([disabled])',
    ) ?? el
  target.focus()
}

const SERVER_FIELD_MAP: Record<string, string> = {
  sourceMeetingId: 'meetingId',
  sourceDecisionItemId: 'decisionItemId',
  sourceProjectId: 'projectId',
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
          // 三态互斥（AC-2）：按形态只带自己那一组字段，另外两组一律不出现
          sourceMeetingId: activeForm.value === 'MEETING' ? Number(form.meetingId) : undefined,
          sourceDecisionItemId:
            activeForm.value === 'MEETING' && form.decisionItemId !== ''
              ? Number(form.decisionItemId)
              : undefined,
          sourceProjectId:
            activeForm.value === 'PROJECT' && form.projectId !== ''
              ? Number(form.projectId)
              : undefined,
          sourceNote:
            activeForm.value === 'NOTE' ? form.note.trim() || undefined : undefined,
          priority: form.priority,
        })
    toast.success(isEdit.value ? '任务已更新' : '任务已创建')
    emit('saved', detail)
    emit('close')
  } catch (error) {
    const apiError = toApiError(error)
    if (apiError.status === 422 && apiError.fieldErrors.length) {
      applyServerErrors(apiError.fieldErrors)
    } else if (
      !isEdit.value &&
      activeForm.value === 'PROJECT' &&
      (apiError.code === 'PROJECT_DISABLED' || apiError.code === 'PROJECT_INVALID')
    ) {
      // 项目清单已过期（被停用/删除）：清空选择并请父级重拉 meta（DESIGN §3.4）
      form.projectId = ''
      clearError('projectId')
      toast.warning(apiError.message || '该项目已停用，请重新选择')
      emit('refresh-meta')
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
    :description="isEdit ? '来源不可修改；改负责人或截止日期请用「改派 / 改期」' : '必填项齐全才会保存'"
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
        <div v-if="isEdit && currentProjectLabel" class="source-readonly">
          <span class="source-readonly__label">来源项目</span>
          <span class="source-readonly__value">{{ currentProjectLabel }}</span>
        </div>
        <AppSelect
          v-if="!isEdit"
          :model-value="form.sourceType"
          :options="sourceOptions"
          label="来源类型"
          required
          :hint="`当前形态：${SOURCE_FORM_LABELS[activeForm]}`"
          @update:model-value="onSourceChange"
        />
        <p v-if="isEdit" class="task-form__hint">
          来源在创建时确定，不可修改（含来源项目）；如需更换来源请新建任务
        </p>
        <p v-if="formSwitched && !isEdit" class="task-form__warn">
          已切换形态，保存后按新形态校验
        </p>

        <template v-if="activeForm === 'MEETING'">
          <template v-if="isEdit">
            <div class="source-readonly">
              <span class="source-readonly__label">来源会议</span>
              <span class="source-readonly__value">{{ currentMeetingLabel }}</span>
            </div>
            <div class="source-readonly">
              <span class="source-readonly__label">关联决议项</span>
              <span class="source-readonly__value">{{ currentDecisionLabel }}</span>
            </div>
          </template>
          <template v-else>
            <div :ref="(el) => setFieldEl('meetingId', el)">
              <AppSelect
                :model-value="form.meetingId"
                :options="meetingOptions"
                label="来源会议"
                required
                placeholder="选择会议"
                :invalid="Boolean(errors.meetingId)"
                :error="errors.meetingId"
                @update:model-value="form.meetingId = $event ? Number($event) : ''; clearError('meetingId')"
              />
            </div>
            <div
              :ref="(el) => setFieldEl('decisionItemId', el)"
              class="task-form__decision"
              tabindex="-1"
            >
              <AppSelect
                :model-value="form.decisionItemId"
                :options="itemOptions"
                label="关联决议项"
                required
                :disabled="form.meetingId === '' || itemsLoading || noSelectableItems"
                :placeholder="decisionPlaceholder"
                hint="已转为任务的决议项不可重复选择"
                :invalid="Boolean(decisionItemError)"
                :error="decisionItemError"
                @update:model-value="onDecisionItem"
              />
              <button v-if="itemsError" type="button" class="task-form__retry" @click="retryItems">
                重试加载决议项
              </button>
            </div>
          </template>
        </template>
        <template v-else-if="activeForm === 'PROJECT'">
          <!-- 编辑态不提供改项目入口（DESIGN §3.4）：项目只在上方只读行回显 -->
          <div v-if="!isEdit" :ref="(el) => setFieldEl('projectId', el)">
            <AppSearchSelect
              :model-value="form.projectId === '' ? '' : String(form.projectId)"
              :options="projectItems"
              label="来源项目"
              required
              :disabled="noProjectAvailable"
              placeholder="选择项目"
              :empty-text="noProjectAvailable ? NO_PROJECT_TEXT : NO_MATCH_TEXT"
              :hint="
                noProjectAvailable
                  ? NO_PROJECT_TEXT
                  : '只列启用项目；项目由管理员在「系统设置 → 项目配置」维护'
              "
              :invalid="Boolean(errors.projectId)"
              :error="errors.projectId"
              @update:model-value="form.projectId = $event ? Number($event) : ''; clearError('projectId')"
            />
          </div>
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
/* 决议项字段容器：select 与「重试」按钮垂直成组；tabindex=-1 让焦点在两者都不可聚焦时仍落在这里 */
.task-form__decision {
  display: flex;
  flex-direction: column;
  gap: var(--sp-2);
  min-width: 0;
}
.task-form__decision:focus-visible {
  outline: 2px solid var(--c-primary);
  outline-offset: 2px;
  border-radius: var(--r-md);
}
/* 加载失败的重试：文字按钮，与行内错误同组，不抢保存按钮的视觉权重 */
.task-form__retry {
  align-self: flex-start;
  padding: 0;
  border: 0;
  background: transparent;
  color: var(--c-primary);
  font-size: var(--fs-caption);
  font-weight: 600;
  cursor: pointer;
  text-decoration: underline;
  text-underline-offset: 2px;
}
.task-form__retry:hover {
  color: var(--c-primary-strong, var(--c-primary));
}
.task-form__retry:focus-visible {
  outline: 2px solid var(--c-primary);
  outline-offset: 2px;
  border-radius: var(--r-sm);
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
