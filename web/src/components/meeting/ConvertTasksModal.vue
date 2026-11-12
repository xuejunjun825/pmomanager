<script setup lang="ts">
/**
 * 转任务弹窗（r7 / AC-9）：勾选的 N 条决议**一次批量**统一挂主题与计划开始。
 *
 * 为什么不是逐行弹窗：逐行挂主题会让「同一次会议、同一批任务」出现 N 种配置，
 * 也把一次写操作拆成 N 个事务。这里把「本轮统一值」写在顶部，并在按钮上再次声明
 * 「应用于全部 N 条」，用户不会误以为只作用于第一行。
 *
 * 失败不清空：主题被停用 / 计划开始晚于某条截止 → 后端 422，错误留在弹窗内，
 * 用户改一处再提交，勾选与已填的值都还在。
 */
import { computed, ref, watch } from 'vue'
import AppButton from '@/components/ui/AppButton.vue'
import AppDatePicker from '@/components/ui/AppDatePicker.vue'
import AppSelect from '@/components/ui/AppSelect.vue'
import Modal from '@/components/ui/Modal.vue'
import { meetingApi } from '@/api/meetings'
import { UNASSIGNED_VALUE } from '@/utils/projectOptions'
import { themeOptions } from '@/utils/themeOptions'
import { toApiError, type ApiError } from '@/api/http'
import type { ConvertResult, DecisionItemRow, ThemeOptionRow } from '@/types/api'

const props = defineProps<{
  open: boolean
  /** 已勾选、尚未转任务的决议行（调用方负责过滤） */
  selected: DecisionItemRow[]
  themes: ThemeOptionRow[]
  /**
   * TECHNICAL §4.3 的 props 表未列此成员，但 emits 只给了 `converted[ConvertResult]`——
   * 即弹窗自己发请求，于是必须知道是哪个会议。由页面传入，不在组件内读路由（隐式耦合更难测）。
   */
  meetingId: number
}>()

const emit = defineEmits<{ close: []; converted: [result: ConvertResult] }>()

const themeSelect = ref<string>(UNASSIGNED_VALUE)
const plannedStartDate = ref<string>('')
const saving = ref(false)
const error = ref<ApiError | null>(null)
const fieldErrors = ref<Record<string, string>>({})

/** 停用主题不出现、不追加「全部主题」（这里不是筛选器），保留「未挂主题」= 不指定 */
const themeSelectOptions = computed(() =>
  themeOptions(props.themes, { allOption: false, includeDisabled: false, includeUnassigned: true }),
)

const count = computed(() => props.selected.length)

watch(
  () => props.open,
  (open) => {
    if (!open) return
    // 每次打开重置：上一次的填写不该悄悄作用于另一批决议
    themeSelect.value = UNASSIGNED_VALUE
    plannedStartDate.value = ''
    error.value = null
    fieldErrors.value = {}
    saving.value = false
  },
)

function close(): void {
  if (saving.value) return
  emit('close')
}

async function submit(): Promise<void> {
  if (saving.value || count.value === 0) return
  saving.value = true
  error.value = null
  fieldErrors.value = {}
  try {
    const themeId =
      themeSelect.value && themeSelect.value !== UNASSIGNED_VALUE ? Number(themeSelect.value) : null
    const result = await meetingApi.convert(props.meetingId, {
      itemIds: props.selected.map((item) => item.id),
      themeId,
      plannedStartDate: plannedStartDate.value || null,
    })
    emit('converted', result)
  } catch (err) {
    const apiErr = toApiError(err)
    error.value = apiErr
    const next: Record<string, string> = {}
    for (const field of apiErr.fieldErrors) next[field.field] = field.message
    fieldErrors.value = next
  } finally {
    saving.value = false
  }
}

/** 按钮文案说清楚「这一批」；result 摘要由页面 toast，避免弹窗内两处重复 */
const summaryText = computed(() => `已勾选 ${count.value} 条决议，主题与计划开始统一应用于全部 ${count.value} 条`)
</script>

<template>
  <Modal
    :open="open"
    title="转为任务"
    size="md"
    :close-on-overlay="!saving"
    @close="close"
  >
    <p class="convert__lead">{{ summaryText }}</p>

    <div class="convert__fields">
      <AppSelect
        v-model="themeSelect"
        label="主题"
        :options="themeSelectOptions"
        :invalid="Boolean(fieldErrors.themeId)"
        :error="fieldErrors.themeId"
        hint="可在任务详情里再单独调整"
      />
      <AppDatePicker
        v-model="plannedStartDate"
        label="计划开始"
        :invalid="Boolean(fieldErrors.plannedStartDate)"
        :error="fieldErrors.plannedStartDate"
        hint="留空则由任务的创建时间起算"
      />
    </div>

    <div v-if="error && Object.keys(fieldErrors).length === 0" class="convert__error" role="alert">
      {{ error.message }}
    </div>

    <ul class="convert__list" aria-label="本次转换的决议事项">
      <li v-for="item in selected" :key="item.id" class="convert__row">
        <span class="convert__desc">{{ item.description }}</span>
        <span class="convert__due num">截止 {{ item.dueDate }}</span>
      </li>
    </ul>

    <template #footer>
      <AppButton variant="secondary" :disabled="saving" @click="close">取消</AppButton>
      <AppButton
        variant="primary"
        :loading="saving"
        loading-text="正在转换…"
        :disabled="count === 0"
        @click="submit"
      >
        转换为任务（{{ count }} 条）
      </AppButton>
    </template>
  </Modal>
</template>

<style scoped>
.convert__lead {
  margin: 0 0 var(--sp-4);
  font-size: var(--fs-body-sm);
  color: var(--c-ink-600);
}
.convert__fields {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: var(--sp-4);
  margin-bottom: var(--sp-4);
}
.convert__error {
  margin-bottom: var(--sp-3);
  padding: var(--sp-3);
  border: 1px solid var(--c-danger-border);
  border-radius: var(--r-md);
  background: var(--c-danger-soft);
  color: var(--c-danger-text);
  font-size: var(--fs-body-sm);
}
.convert__list {
  margin: 0;
  padding: var(--sp-2);
  list-style: none;
  border: 1px solid var(--c-line);
  border-radius: var(--r-md);
  max-height: 220px;
  overflow: auto;
}
.convert__row {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  gap: var(--sp-3);
  padding: var(--sp-2) var(--sp-2);
  border-radius: var(--r-sm);
}
.convert__row + .convert__row {
  border-top: 1px dashed var(--c-line);
}
.convert__desc {
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  font-size: var(--fs-body-sm);
}
.convert__due {
  flex: none;
  font-size: var(--fs-caption);
  color: var(--c-ink-400);
}
</style>
