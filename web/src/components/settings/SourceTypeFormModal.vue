<script setup lang="ts">
/**
 * 新增 / 编辑来源弹窗（DESIGN §3.2）：480 宽，编码只读、名称 2–10、形态单选、8 色板、排序 0–99。
 * - 编码由后端生成，页面只回显；编辑态回传 code 定位，不允许改编码；
 * - 409 落在看得见的位置：重名 → 名称字段；形态冲突 → 顶部摘要 + 形态字段；上限 → 顶部摘要；
 * - 关闭不弹二次确认（DESIGN 明确要求直接丢弃草稿）。
 */
import { computed, nextTick, reactive, ref, watch } from 'vue'
import AppButton from '@/components/ui/AppButton.vue'
import AppInput from '@/components/ui/AppInput.vue'
import Modal from '@/components/ui/Modal.vue'
import SourceColorSwatches from './SourceColorSwatches.vue'
import { sourceTypesApi } from '@/api/sourceTypes'
import { toApiError } from '@/api/http'
import type { SourcePaletteEntry, SourceTypePayload, SourceTypeRow } from '@/types/api'
import type { SourceForm } from '@/types/enums'

const props = withDefaults(
  defineProps<{
    open: boolean
    row?: SourceTypeRow | null
    palette: SourcePaletteEntry[]
    /** 新增时的默认排序（当前最大 sort + 1，封顶 99），由表格计算后传入 */
    nextSort?: number
  }>(),
  { row: null, nextSort: 0 },
)

const emit = defineEmits<{ saved: [row: SourceTypeRow]; close: [] }>()

const FORM_OPTIONS: { value: SourceForm; title: string; hint: string }[] = [
  { value: 'NOTE', title: '自由说明', hint: '建任务时填 1–200 字' },
  { value: 'MEETING', title: '需绑定会议', hint: '必须选会议与决议项' },
]

const MAX_NAME = 10
const MIN_NAME = 2
const name = ref('')
const form = ref<SourceForm>('NOTE')
const color = ref('')
const sortValue = ref('')
const summary = ref('')
const limitReached = ref(false)
const saving = ref(false)
const fieldErrors = reactive<{ name?: string; form?: string; color?: string; sort?: string }>({})
const nameRef = ref<InstanceType<typeof AppInput> | null>(null)

const isEdit = computed(() => props.row !== null && props.row !== undefined)
/** 内置「会议决议」的形态被锁定：一键转任务依赖 MEETING，形态不可改 */
const formLocked = computed(() => props.row?.locked === true && props.row.form === 'MEETING')
/** 按 code point 计数：emoji 与中文都算 1 个字，和输入框 maxlength 的 UTF-16 计数解耦 */
const nameCount = computed(() => Array.from(name.value.trim()).length)

function clearErrors(): void {
  fieldErrors.name = undefined
  fieldErrors.form = undefined
  fieldErrors.color = undefined
  fieldErrors.sort = undefined
}

/** 打开时播种：编辑回填当前行，新增回到默认形态 + 色板首色 */
function seed(): void {
  const row = props.row
  summary.value = ''
  limitReached.value = false
  clearErrors()
  saving.value = false
  if (row) {
    name.value = row.name
    form.value = row.form
    color.value = row.color
    sortValue.value = String(row.sort)
  } else {
    name.value = ''
    form.value = 'NOTE'
    color.value = props.palette[0]?.color ?? ''
    sortValue.value = String(Math.min(Math.max(props.nextSort, 0), 99))
  }
}

watch(
  () => props.open,
  async (open) => {
    if (!open) return
    seed()
    await nextTick()
    nameRef.value?.focus()
  },
  { immediate: true },
)

function close(): void {
  if (saving.value) return
  emit('close')
}

function applyError(error: ReturnType<typeof toApiError>): void {
  if (error.code === 'SOURCE_NAME_DUPLICATE') {
    fieldErrors.name = '名称已存在，请换一个'
    return
  }
  if (error.code === 'SOURCE_FORM_CONFLICT') {
    summary.value = error.message || '该形态与已有任务冲突'
    fieldErrors.form = summary.value
    // 服务端拒绝了形态切换：表单回到原形态，避免连续提交同一个非法请求
    if (props.row) form.value = props.row.form
    return
  }
  if (error.code === 'SOURCE_LIMIT_REACHED') {
    limitReached.value = true
    summary.value = error.message || '来源类型最多 20 个'
    return
  }
  if (error.code === 'SOURCE_BUILTIN_PROTECTED' || error.code === 'SOURCE_IN_USE' || error.status === 403) {
    summary.value = error.message || '该操作不被允许'
    return
  }
  if (error.fieldErrors.length) {
    for (const item of error.fieldErrors) {
      if (item.field === 'name' || item.field === 'form' || item.field === 'color' || item.field === 'sort') {
        fieldErrors[item.field] = item.message
      }
    }
    if (!fieldErrors.name && !fieldErrors.form && !fieldErrors.color && !fieldErrors.sort) {
      summary.value = error.message || '提交的内容不合法'
    }
    return
  }
  summary.value = error.message || '保存失败，请重试'
}

async function submit(): Promise<void> {
  if (saving.value || limitReached.value) return
  summary.value = ''
  clearErrors()

  const trimmed = name.value.trim()
  const length = Array.from(trimmed).length
  if (length < MIN_NAME || length > MAX_NAME) {
    fieldErrors.name = `名称需为 ${MIN_NAME}–${MAX_NAME} 个字`
  }
  if (!color.value) {
    fieldErrors.color = '请选择颜色'
  }
  if (sortValue.value !== '') {
    const sort = Number(sortValue.value)
    if (!/^\d{1,2}$/.test(sortValue.value) || !Number.isInteger(sort) || sort < 0 || sort > 99) {
      fieldErrors.sort = '排序需在 0–99 之间'
    }
  }
  if (fieldErrors.name || fieldErrors.color || fieldErrors.sort) return

  const payload: SourceTypePayload = {
    name: trimmed,
    form: form.value,
    color: color.value,
    sort: sortValue.value === '' ? null : Number(sortValue.value),
  }

  saving.value = true
  try {
    const saved = props.row
      ? await sourceTypesApi.update(props.row.code, payload)
      : await sourceTypesApi.create(payload)
    emit('saved', saved)
  } catch (error) {
    applyError(toApiError(error))
  } finally {
    saving.value = false
  }
}
</script>

<template>
  <Modal
    :open="open"
    :title="isEdit ? '编辑来源' : '新增来源'"
    :description="isEdit ? '修改立即生效；编码创建后不可修改。' : '自定义来源会获得稳定编码，创建后不可修改。'"
    size="md"
    @close="close"
  >
    <form class="source-form" novalidate @submit.prevent="submit">
      <p v-if="summary" class="source-form__summary" role="alert">{{ summary }}</p>

      <AppInput
        ref="nameRef"
        v-model="name"
        label="名称"
        required
        :maxlength="MAX_NAME"
        placeholder="例如：客户投诉"
        :invalid="!!fieldErrors.name"
        :error="fieldErrors.name"
        hint="2–10 个字，在任务池与标签中展示"
      >
        <template #suffix>
          <span class="source-form__count" :class="{ 'is-max': nameCount >= MAX_NAME }">{{ nameCount }}/{{ MAX_NAME }}</span>
        </template>
      </AppInput>

      <div class="source-form__field">
        <span class="source-form__label">编码</span>
        <div v-if="isEdit" class="source-form__code">
          <span class="mono">{{ row?.code }}</span>
          <span class="source-form__code-note">创建后不可修改</span>
        </div>
        <div v-else class="source-form__code source-form__code--placeholder">保存后自动生成（创建后不可修改）</div>
      </div>

      <div class="source-form__field" role="radiogroup" aria-label="形态">
        <span class="source-form__label">形态<span class="source-form__req" aria-hidden="true">*</span></span>
        <div class="form-cards">
          <label
            v-for="option in FORM_OPTIONS"
            :key="option.value"
            class="form-card"
            :class="{
              'is-checked': form === option.value,
              'is-disabled': formLocked,
            }"
          >
            <input
              v-model="form"
              class="form-card__radio"
              type="radio"
              name="source-form"
              :value="option.value"
              :disabled="formLocked"
            />
            <span class="form-card__body">
              <span class="form-card__title">{{ option.title }}</span>
              <span class="form-card__hint">{{ option.hint }}</span>
            </span>
          </label>
        </div>
        <p v-if="formLocked" class="source-form__hint">会议决议一键转任务依赖该形态，不可修改</p>
        <p v-if="fieldErrors.form" class="source-form__error" role="alert">{{ fieldErrors.form }}</p>
      </div>

      <div class="source-form__field">
        <span class="source-form__label">颜色<span class="source-form__req" aria-hidden="true">*</span></span>
        <SourceColorSwatches v-model="color" :options="palette" />
        <p v-if="fieldErrors.color" class="source-form__error" role="alert">{{ fieldErrors.color }}</p>
      </div>

      <AppInput
        v-model="sortValue"
        type="number"
        inputmode="numeric"
        label="排序"
        hint="0–99，数字越小越靠前；留空自动排到末尾"
        :invalid="!!fieldErrors.sort"
        :error="fieldErrors.sort"
      />
    </form>

    <template #footer>
      <AppButton variant="secondary" :disabled="saving" @click="close">取消</AppButton>
      <AppButton
        variant="primary"
        :loading="saving"
        loading-text="保存中…"
        :disabled="limitReached"
        @click="submit"
      >
        保存
      </AppButton>
    </template>
  </Modal>
</template>

<style scoped>
.source-form {
  display: flex;
  flex-direction: column;
  gap: var(--sp-4);
}
.source-form__summary {
  margin: 0;
  padding: 10px var(--sp-3);
  border: 1px solid var(--c-danger-border);
  border-radius: var(--r-md);
  background: var(--c-danger-soft);
  color: var(--c-danger-text);
  font-size: var(--fs-body-sm);
  line-height: var(--lh-body-sm);
}
.source-form__field {
  display: flex;
  flex-direction: column;
  gap: var(--sp-1);
}
.source-form__label {
  font-size: var(--fs-body-sm);
  font-weight: 500;
  line-height: var(--lh-body-sm);
  color: var(--c-ink-700);
}
.source-form__req {
  margin-left: 2px;
  color: var(--c-danger);
}
.source-form__count {
  font-size: var(--fs-micro);
  line-height: var(--lh-micro);
  font-variant-numeric: tabular-nums;
  color: var(--c-ink-500);
  white-space: nowrap;
}
.source-form__count.is-max {
  color: var(--c-warn);
}
.source-form__code {
  display: flex;
  align-items: center;
  gap: var(--sp-2);
  height: 32px;
  padding: 0 var(--sp-3);
  border: 1px solid var(--c-line);
  border-radius: var(--r-md);
  background: var(--c-line-soft);
  font-size: var(--fs-caption);
  color: var(--c-ink-600);
}
.source-form__code .mono {
  font-family: var(--font-num);
  font-size: var(--fs-caption);
  letter-spacing: 0.02em;
  text-transform: uppercase;
  color: var(--c-ink-700);
}
.source-form__code--placeholder {
  color: var(--c-ink-500);
}
.source-form__code-note {
  margin-left: auto;
  font-size: var(--fs-micro);
  color: var(--c-ink-400);
}
.source-form__hint {
  margin: 0;
  font-size: var(--fs-caption);
  line-height: var(--lh-caption);
  color: var(--c-ink-500);
}
.source-form__error {
  margin: 0;
  font-size: var(--fs-caption);
  line-height: var(--lh-caption);
  color: var(--c-danger-text);
}

.form-cards {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: var(--sp-3);
}
.form-card {
  display: flex;
  align-items: flex-start;
  gap: var(--sp-2);
  min-height: 56px;
  padding: 10px var(--sp-3);
  border: 1px solid var(--c-line);
  border-radius: var(--r-lg);
  background: var(--c-surface);
  cursor: pointer;
  transition:
    border-color var(--t-hover) var(--ease),
    background var(--t-hover) var(--ease);
}
.form-card:hover:not(.is-disabled) {
  border-color: var(--c-primary-border);
}
.form-card.is-checked {
  border-color: var(--c-primary);
  background: var(--c-primary-soft);
}
.form-card.is-disabled {
  cursor: not-allowed;
  background: var(--c-line-soft);
}
.form-card:focus-within {
  box-shadow: var(--focus-ring);
}
.form-card__radio {
  position: absolute;
  width: 1px;
  height: 1px;
  margin: 0;
  opacity: 0;
  pointer-events: none;
}
.form-card__body {
  display: flex;
  flex-direction: column;
  gap: 2px;
  min-width: 0;
}
.form-card__title {
  font-size: var(--fs-body);
  font-weight: 600;
  line-height: var(--lh-body);
  color: var(--c-ink-900);
}
.form-card.is-disabled .form-card__title {
  color: var(--c-ink-400);
}
.form-card__hint {
  font-size: var(--fs-caption);
  line-height: var(--lh-caption);
  color: var(--c-ink-500);
}
.form-card.is-disabled .form-card__hint {
  color: var(--c-ink-400);
}
</style>
