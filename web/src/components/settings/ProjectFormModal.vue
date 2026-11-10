<script setup lang="ts">
/**
 * 新增 / 编辑项目弹窗（DESIGN §3.2 / TECHNICAL §4.4）：480 宽、编码只读、名称按码点计数、排序 0–99。
 * - 编码由服务端生成（P%05d），弹窗只回显；编辑态旁注「创建后不可修改」；
 * - 422 落字段（name/sort），409 PROJECT_NAME_DUPLICATE 落名称字段，
 *   409 PROJECT_LIMIT_REACHED 落顶部摘要（并发下另一管理员刚加满），网络错误摘要 + 可重试；
 * - 关闭直接丢弃草稿（与来源弹窗同约定，不弹二次确认）。
 */
import { computed, nextTick, reactive, ref, watch } from 'vue'
import AppButton from '@/components/ui/AppButton.vue'
import AppInput from '@/components/ui/AppInput.vue'
import Modal from '@/components/ui/Modal.vue'
import { projectsApi } from '@/api/projects'
import { toApiError, type ApiError } from '@/api/http'
import type { ProjectPayload, ProjectRow } from '@/types/api'

const props = withDefaults(
  defineProps<{
    open: boolean
    row?: ProjectRow | null
    /** 新增时的默认排序（当前最大 sort + 1，封顶 99），由表格计算后传入 */
    nextSort?: number
  }>(),
  { row: null, nextSort: 0 },
)

const emit = defineEmits<{ saved: [row: ProjectRow]; close: [] }>()

const MIN_NAME = 2
const MAX_NAME = 20
const MAX_SORT = 99

const name = ref('')
const sortValue = ref('')
const enabled = ref(true)
const summary = ref('')
const limitReached = ref(false)
const saving = ref(false)
const fieldErrors = reactive<{ name?: string; sort?: string }>({})
const nameRef = ref<InstanceType<typeof AppInput> | null>(null)

const isEdit = computed(() => props.row !== null && props.row !== undefined)
/** 按 Unicode 码点计数：中文与 emoji 都算 1 个字，与输入框的 UTF-16 计数解耦 */
const nameCount = computed(() => Array.from(name.value.trim()).length)

function clearErrors(): void {
  fieldErrors.name = undefined
  fieldErrors.sort = undefined
}

/** 打开时播种：编辑回填当前行，新增给默认排序与「启用」 */
function seed(): void {
  summary.value = ''
  limitReached.value = false
  clearErrors()
  saving.value = false
  if (props.row) {
    name.value = props.row.name
    sortValue.value = String(props.row.sort)
    enabled.value = props.row.enabled
  } else {
    name.value = ''
    sortValue.value = String(Math.min(Math.max(props.nextSort, 0), MAX_SORT))
    enabled.value = true
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

function applyError(error: ApiError): void {
  if (error.code === 'PROJECT_NAME_DUPLICATE') {
    fieldErrors.name = '名称已存在，请换一个'
    return
  }
  if (error.code === 'PROJECT_LIMIT_REACHED') {
    limitReached.value = true
    summary.value = error.message || '项目最多 200 个'
    return
  }
  if (error.code === 'PROJECT_IN_USE') {
    summary.value = error.message || '该项目已被任务使用，只能停用'
    return
  }
  if (error.code === 'PROJECT_INVALID') {
    summary.value = error.message || '项目不存在或已被删除，请刷新后重试'
    return
  }
  if (error.status === 403) {
    summary.value = error.message || '你没有管理项目的权限'
    return
  }
  if (error.fieldErrors.length) {
    for (const item of error.fieldErrors) {
      if (item.field === 'name' || item.field === 'sort') fieldErrors[item.field] = item.message
    }
    if (!fieldErrors.name && !fieldErrors.sort) {
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
  let sort: number | null = null
  if (sortValue.value !== '') {
    const value = Number(sortValue.value)
    if (!/^\d{1,2}$/.test(sortValue.value) || !Number.isInteger(value) || value < 0 || value > MAX_SORT) {
      fieldErrors.sort = '排序需在 0–99 之间'
    } else {
      sort = value
    }
  }
  if (fieldErrors.name || fieldErrors.sort) return

  const payload: ProjectPayload = { name: trimmed, sort, enabled: enabled.value }

  saving.value = true
  try {
    const saved = props.row
      ? await projectsApi.update(props.row.id, payload)
      : await projectsApi.create(payload)
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
    :title="isEdit ? '编辑项目' : '新建项目'"
    :description="
      isEdit ? '修改立即生效；编码创建后不可修改。' : '项目编码由系统生成，创建后不可修改。'
    "
    size="md"
    @close="close"
  >
    <form class="project-form" novalidate @submit.prevent="submit">
      <p v-if="summary" class="project-form__summary" role="alert">{{ summary }}</p>

      <AppInput
        ref="nameRef"
        v-model="name"
        label="名称"
        required
        placeholder="例如：城市更新一期"
        :invalid="!!fieldErrors.name"
        :error="fieldErrors.name"
        hint="2–20 个字，在任务与筛选里展示"
      >
        <template #suffix>
          <span class="project-form__count" :class="{ 'is-max': nameCount > MAX_NAME }">
            {{ nameCount }}/{{ MAX_NAME }}
          </span>
        </template>
      </AppInput>

      <div class="project-form__field">
        <span class="project-form__label">编码</span>
        <div v-if="isEdit" class="project-form__code">
          <span class="mono">{{ row?.code }}</span>
          <span class="project-form__code-note">创建后不可修改</span>
        </div>
        <div v-else class="project-form__code project-form__code--placeholder">
          保存后自动生成（创建后不可修改）
        </div>
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

      <div class="project-form__field">
        <label class="switch">
          <input v-model="enabled" type="checkbox" class="switch__input" role="switch" />
          <span class="switch__track" aria-hidden="true"><span class="switch__dot" /></span>
          <span class="switch__label">{{ enabled ? '已启用' : '已停用' }}</span>
        </label>
        <p class="project-form__hint">停用后新建任务里选不到该项目，已有任务照常展示。</p>
      </div>
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
.project-form {
  display: flex;
  flex-direction: column;
  gap: var(--sp-4);
}
.project-form__summary {
  margin: 0;
  padding: 10px var(--sp-3);
  border: 1px solid var(--c-danger-border);
  border-radius: var(--r-md);
  background: var(--c-danger-soft);
  color: var(--c-danger-text);
  font-size: var(--fs-body-sm);
  line-height: var(--lh-body-sm);
}
.project-form__field {
  display: flex;
  flex-direction: column;
  gap: var(--sp-1);
}
.project-form__label {
  font-size: var(--fs-body-sm);
  font-weight: 500;
  line-height: var(--lh-body-sm);
  color: var(--c-ink-700);
}
.project-form__count {
  font-size: var(--fs-micro);
  line-height: var(--lh-micro);
  font-variant-numeric: tabular-nums;
  color: var(--c-ink-500);
  white-space: nowrap;
}
.project-form__count.is-max {
  color: var(--c-warn);
}
.project-form__code {
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
.project-form__code .mono {
  font-family: var(--font-num);
  font-size: var(--fs-caption);
  letter-spacing: 0.02em;
  text-transform: uppercase;
  color: var(--c-ink-700);
}
.project-form__code--placeholder {
  color: var(--c-ink-500);
}
.project-form__code-note {
  margin-left: auto;
  font-size: var(--fs-micro);
  color: var(--c-ink-400);
}
.project-form__hint {
  margin: 0;
  font-size: var(--fs-caption);
  line-height: var(--lh-caption);
  color: var(--c-ink-500);
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
  width: 1px;
  height: 1px;
  opacity: 0;
}
.switch__track {
  position: relative;
  width: 36px;
  height: 20px;
  border-radius: var(--r-pill);
  background: var(--c-line);
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
</style>
