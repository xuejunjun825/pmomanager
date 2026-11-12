<script setup lang="ts">
/**
 * 任务池筛选条（DESIGN §4.2）：控件高 32，六个条件可组合，全部进 URL。
 * 生效条件用 chips 回显，可单独移除或一键清空 —— 用户永远看得见「现在筛的是什么」。
 */
import { computed } from 'vue'
import AppInput from '@/components/ui/AppInput.vue'
import AppSearchSelect from '@/components/ui/AppSearchSelect.vue'
import AppSelect from '@/components/ui/AppSelect.vue'
import AppButton from '@/components/ui/AppButton.vue'
import type { SourceType, TaskStatus } from '@/types/enums'
import { TASK_STATUS_LABELS } from '@/types/enums'
import type { ProjectOptionRow, SourceTypeOption, ThemeOptionRow, UserOption } from '@/types/api'
import { sourceOptions as buildSourceOptions } from '@/utils/sourceOptions'
import { themeOptions as buildThemeOptions } from '@/utils/themeOptions'
import {
  UNASSIGNED_VALUE,
  projectOptions as buildProjectOptions,
  type ProjectOption,
} from '@/utils/projectOptions'

export interface PoolFilters {
  status: TaskStatus | ''
  ownerId: number | ''
  sourceType: SourceType | ''
  /** `'<id>'` | `'none'`（未指定项目）| `''`（全部）—— 与 URL 短键 `project` 同值域 */
  project: string
  /** `'<id>'` | `'none'`（未挂主题）| `''`（全部）—— 与 URL 短键 `theme` 同值域 */
  theme: string
  meetingId: number | ''
  overdue: boolean | ''
  keyword: string
}

const props = withDefaults(
  defineProps<{
    modelValue: PoolFilters
    owners: UserOption[]
    meetings: Array<{ id: number; title: string }>
    /** meta.sourceTypes 全量（含已停用）：停用来源仍要能筛历史任务（AC-5） */
    sourceTypes?: SourceTypeOption[]
    /** meta.projects 全量（含已停用）：停用项目仍要能筛历史任务（AC-5） */
    projects?: ProjectOptionRow[]
    /** meta.themes 全量（含已停用）：停用主题仍要能筛历史任务 */
    themes?: ThemeOptionRow[]
    loading?: boolean
  }>(),
  { sourceTypes: () => [], projects: () => [], themes: () => [], loading: false },
)

const emit = defineEmits<{ 'update:modelValue': [value: PoolFilters] }>()

const STATUS_OPTIONS = [
  { label: '全部状态', value: '' },
  ...(Object.keys(TASK_STATUS_LABELS) as TaskStatus[]).map((status) => ({
    label: TASK_STATUS_LABELS[status],
    value: status,
  })),
]

/**
 * 来源筛选读 meta 全量（AC-5）：启用项按 sort 在前，停用项按 sort 在后并标注「（已停用）」——
 * 停用来源仍要能筛出历史任务，不得因为停用把选项删掉。
 */
const sourceOptions = computed(() => buildSourceOptions(props.sourceTypes))

/**
 * 项目筛选的选项来自唯一投影（AC-5）：含停用项与「未指定项目」档，
 * 与矩阵 / 看板同一函数同一参数，页面不得自己过滤停用项或拼标签。
 */
const projectItems = computed<ProjectOption[]>(() =>
  buildProjectOptions(props.projects, {
    allOption: true,
    includeDisabled: true,
    includeUnassigned: true,
  }),
)

/** 主题筛选的选项同样来自唯一投影：含停用项与「未挂主题」档，与表单/甘特同一函数 */
const themeItems = computed(() =>
  buildThemeOptions(props.themes, {
    allOption: true,
    includeDisabled: true,
    includeUnassigned: true,
  }),
)

const OVERDUE_OPTIONS = [
  { label: '是否逾期', value: '' },
  { label: '仅逾期', value: 'true' },
  { label: '未逾期', value: 'false' },
]

const ownerOptions = computed(() => [
  { label: '全部负责人', value: '' },
  ...(Array.isArray(props.owners) ? props.owners : []).map((owner) => ({
    label: owner.name,
    value: owner.id,
  })),
])

const meetingOptions = computed(() => [
  { label: '全部会议', value: '' },
  ...(Array.isArray(props.meetings) ? props.meetings : []).map((meeting) => ({
    label: meeting.title,
    value: meeting.id,
  })),
])

function patch(part: Partial<PoolFilters>): void {
  emit('update:modelValue', { ...props.modelValue, ...part })
}

function onOwner(value: string): void {
  patch({ ownerId: value ? Number(value) : '' })
}

function onMeeting(value: string): void {
  patch({ meetingId: value ? Number(value) : '' })
}

function onOverdue(value: string): void {
  patch({ overdue: value === '' ? '' : value === 'true' })
}

interface Chip {
  key: keyof PoolFilters
  label: string
}

const chips = computed<Chip[]>(() => {
  const filter = props.modelValue
  const list: Chip[] = []
  if (filter.status) list.push({ key: 'status', label: TASK_STATUS_LABELS[filter.status] })
  if (filter.ownerId !== '') {
    const owner = props.owners.find((item) => item.id === filter.ownerId)
    list.push({ key: 'ownerId', label: owner ? owner.name : `负责人 #${filter.ownerId}` })
  }
  if (filter.sourceType) {
    const source = props.sourceTypes.find((item) => item.value === filter.sourceType)
    list.push({ key: 'sourceType', label: source ? source.label : filter.sourceType })
  }
  if (filter.project) {
    // chip 文案直接取投影后的选项（停用后缀只有一处），不再拼第二份文案
    const project = projectItems.value.find((item) => item.value === filter.project)
    const fallback = filter.project === UNASSIGNED_VALUE ? '未指定项目' : `项目 #${filter.project}`
    list.push({ key: 'project', label: `项目：${project?.label ?? fallback}` })
  }
  if (filter.theme) {
    const theme = themeItems.value.find((item) => item.value === filter.theme)
    const fallback = filter.theme === UNASSIGNED_VALUE ? '未挂主题' : `主题 #${filter.theme}`
    list.push({ key: 'theme', label: `主题：${theme?.label ?? fallback}` })
  }
  if (filter.meetingId !== '') {
    const meeting = props.meetings.find((item) => item.id === filter.meetingId)
    list.push({ key: 'meetingId', label: meeting ? meeting.title : `会议 #${filter.meetingId}` })
  }
  if (filter.overdue !== '') list.push({ key: 'overdue', label: filter.overdue ? '仅逾期' : '未逾期' })
  if (filter.keyword.trim()) list.push({ key: 'keyword', label: `关键词：${filter.keyword.trim()}` })
  return list
})

function removeChip(key: keyof PoolFilters): void {
  if (key === 'keyword') patch({ keyword: '' })
  else if (key === 'overdue') patch({ overdue: '' })
  else if (key === 'status') patch({ status: '' })
  else if (key === 'sourceType') patch({ sourceType: '' })
  else if (key === 'project') patch({ project: '' })
  else if (key === 'theme') patch({ theme: '' })
  else if (key === 'ownerId') patch({ ownerId: '' })
  else patch({ meetingId: '' })
}

function clearAll(): void {
  emit('update:modelValue', {
    status: '',
    ownerId: '',
    sourceType: '',
    project: '',
    theme: '',
    meetingId: '',
    overdue: '',
    keyword: '',
  })
}
</script>

<template>
  <div class="filter">
    <div class="filter__row">
      <AppInput
        class="filter__search"
        :model-value="modelValue.keyword"
        size="compact"
        type="search"
        placeholder="搜索标题"
        aria-label="搜索标题"
        @update:model-value="patch({ keyword: $event })"
      />
      <AppSelect
        :model-value="modelValue.status"
        :options="STATUS_OPTIONS"
        size="compact"
        aria-label="状态筛选"
        @update:model-value="patch({ status: $event as TaskStatus | '' })"
      />
      <AppSelect
        :model-value="modelValue.ownerId"
        :options="ownerOptions"
        size="compact"
        aria-label="负责人筛选"
        @update:model-value="onOwner"
      />
      <AppSelect
        :model-value="modelValue.sourceType"
        :options="sourceOptions"
        size="compact"
        aria-label="来源类型筛选"
        @update:model-value="patch({ sourceType: $event as SourceType | '' })"
      />
      <!-- 项目从属于来源：紧邻来源位之后、会议之前（DESIGN §3.7），宽 148 与其他控件同高 -->
      <AppSearchSelect
        class="filter__project"
        :model-value="modelValue.project"
        :options="projectItems"
        size="compact"
        aria-label="项目筛选"
        empty-text="没有匹配的项目"
        @update:model-value="patch({ project: $event })"
      />
      <AppSelect
        class="filter__theme"
        :model-value="modelValue.theme"
        :options="themeItems"
        size="compact"
        aria-label="主题筛选"
        @update:model-value="patch({ theme: $event })"
      />
      <AppSelect
        :model-value="modelValue.meetingId"
        :options="meetingOptions"
        size="compact"
        aria-label="来源会议筛选"
        @update:model-value="onMeeting"
      />
      <AppSelect
        :model-value="modelValue.overdue === '' ? '' : String(modelValue.overdue)"
        :options="OVERDUE_OPTIONS"
        size="compact"
        aria-label="逾期筛选"
        @update:model-value="onOverdue"
      />
    </div>

    <div v-if="chips.length" class="filter__chips">
      <button
        v-for="chip in chips"
        :key="chip.key"
        type="button"
        class="chip"
        :aria-label="`移除筛选：${chip.label}`"
        @click="removeChip(chip.key)"
      >
        <span>{{ chip.label }}</span>
        <span class="chip__x" aria-hidden="true">×</span>
      </button>
      <AppButton variant="ghost" size="compact" @click="clearAll">清除全部</AppButton>
    </div>
  </div>
</template>

<style scoped>
.filter {
  display: flex;
  flex-direction: column;
  gap: var(--sp-3);
}
.filter__row {
  display: flex;
  flex-wrap: wrap;
  align-items: flex-end;
  gap: var(--sp-2);
}
.filter__search {
  width: 240px;
}
/* 筛选行合计 966 ≤ 1008（DESIGN §1.6 几何表）：项目位固定 148，不参与伸缩 */
.filter__project {
  width: 148px;
  flex: none;
}
/* 主题位 168：放得下「全部主题 / 未挂主题 / 各主题名（已停用）」 */
.filter__theme {
  width: 168px;
  flex: none;
}
.filter__chips {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: var(--sp-2);
}
.chip {
  display: inline-flex;
  align-items: center;
  gap: var(--sp-1);
  height: 24px;
  padding: 0 var(--sp-2);
  border: 1px solid var(--c-line);
  border-radius: var(--r-pill);
  background: var(--c-surface);
  color: var(--c-ink-700);
  font-size: var(--fs-micro);
  cursor: pointer;
  transition: border-color var(--t-hover) var(--ease), color var(--t-hover) var(--ease);
}
.chip:hover {
  border-color: var(--c-primary-border);
  color: var(--c-primary);
}
.chip:focus-visible {
  outline: 2px solid var(--c-primary);
  outline-offset: 1px;
}
.chip__x {
  font-size: var(--fs-caption);
  color: var(--c-ink-400);
}
.chip:hover .chip__x {
  color: var(--c-primary);
}
/* ≤768（钉钉 H5）：每行 2 个控件，项目下拉占满半宽（DESIGN §6） */
@media (max-width: 768px) {
  .filter__search {
    width: 100%;
  }
  .filter__row > * {
    flex: 1 1 calc(50% - var(--sp-2));
    min-width: 0;
  }
  .filter__project {
    width: auto;
  }
  .filter__theme {
    width: auto;
  }
}
</style>
