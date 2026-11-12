<script setup lang="ts">
/**
 * 主题新建 / 编辑弹窗（DESIGN §3.3）：名称唯一、8 色复用来源色板、排序 0–99、启停开关。
 *
 * - 颜色只能从后端 palette 选，色名与色块同时出现（不靠色觉）；
 * - 名称按码点计数 2–20，与服务端一致；重复名/上限/被引用等错误回填到字段或摘要；
 * - 保存成功 emit `saved` 后由父组件刷新列表，本组件不自己拉数据。
 */
import { computed, nextTick, ref, watch } from 'vue'
import AppButton from '@/components/ui/AppButton.vue'
import AppInput from '@/components/ui/AppInput.vue'
import Modal from '@/components/ui/Modal.vue'
import SourceColorSwatches from './SourceColorSwatches.vue'
import { themesApi } from '@/api/themes'
import { toApiError } from '@/api/http'
import type { SourcePaletteEntry, ThemeRow } from '@/types/api'

const MIN_NAME = 2
const MAX_NAME = 20
const MAX_SORT = 99

const props = withDefaults(
  defineProps<{
    open: boolean
    row?: ThemeRow | null
    nextSort?: number
  }>(),
  { row: null, nextSort: 0 },
)

const emit = defineEmits<{ saved: [value: ThemeRow]; close: [] }>()

const name = ref('')
const color = ref('')
const sortValue = ref('0')
const enabled = ref(true)
const saving = ref(false)
const summary = ref('')
const limitReached = ref(false)
const fieldErrors = ref<{ name?: string; color?: string; sort?: string }>({})
const palette = ref<SourcePaletteEntry[]>([])
const paletteLoading = ref(false)
const nameRef = ref<InstanceType<typeof AppInput> | null>(null)

const isEdit = computed(() => Boolean(props.row))
const title = computed(() => (isEdit.value ? '编辑主题' : '新建主题'))
const nameCount = computed(() => Array.from(name.value).length)

function countOf(value: string): number {
  return Array.from(value).length
}

function seed(): void {
  summary.value = ''
  limitReached.value = false
  fieldErrors.value = {}
  if (props.row) {
    name.value = props.row.name
    color.value = props.row.color
    sortValue.value = String(props.row.sort ?? 0)
    enabled.value = props.row.enabled
  } else {
    name.value = ''
    color.value = palette.value[0]?.color ?? ''
    sortValue.value = String(props.nextSort ?? 0)
    enabled.value = true
  }
}

async function loadPalette(): Promise<void> {
  if (palette.value.length || paletteLoading.value) return
  paletteLoading.value = true
  try {
    palette.value = await themesApi.palette()
    if (!props.row && !color.value) color.value = palette.value[0]?.color ?? ''
  } catch (error) {
    summary.value = toApiError(error).message
  } finally {
    paletteLoading.value = false
  }
}

watch(
  () => props.open,
  (open) => {
    if (!open) return
    seed()
    void loadPalette()
    void nextTick(() => nameRef.value?.focus?.())
  },
  { immediate: true },
)

function validate(): boolean {
  fieldErrors.value = {}
  const trimmed = name.value.trim()
  if (countOf(trimmed) < MIN_NAME || countOf(trimmed) > MAX_NAME) {
    fieldErrors.value.name = `名称需 ${MIN_NAME}–${MAX_NAME} 个字`
  }
  if (!color.value) fieldErrors.value.color = '请选择一个颜色'
  const sort = Number(sortValue.value)
  if (!/^\d{1,2}$/.test(sortValue.value.trim()) || sort > MAX_SORT) {
    fieldErrors.value.sort = `排序需在 0–${MAX_SORT} 之间`
  }
  return Object.keys(fieldErrors.value).length === 0
}

function applyError(error: unknown): void {
  const apiError = toApiError(error)
  switch (apiError.code) {
    case 'THEME_NAME_DUPLICATE':
      fieldErrors.value.name = '名称已存在，请换一个'
      return
    case 'THEME_LIMIT_REACHED':
      limitReached.value = true
      summary.value = '已达 100 个主题上限，删除或停用不再使用的主题后再新建。'
      return
    case 'THEME_IN_USE':
      summary.value = '该主题已被任务引用，只能停用不能删除。'
      return
    case 'THEME_INVALID':
      summary.value = apiError.message
      return
    case 'FORBIDDEN':
      summary.value = '只有管理员可以维护主题。'
      return
    default:
      break
  }
  if (apiError.status === 422 && apiError.fieldErrors?.length) {
    const next: { name?: string; color?: string; sort?: string } = {}
    for (const item of apiError.fieldErrors) {
      if (item.field === 'name') next.name = item.message
      else if (item.field === 'color') next.color = item.message
      else if (item.field === 'sort') next.sort = item.message
    }
    fieldErrors.value = { ...fieldErrors.value, ...next }
    summary.value = `还有 ${apiError.fieldErrors.length} 项需要修改`
    return
  }
  summary.value = apiError.message
}

async function submit(): Promise<void> {
  summary.value = ''
  if (saving.value) return
  if (!validate()) {
    summary.value = '还有 1 项需要修改'
    return
  }
  saving.value = true
  const payload = {
    name: name.value.trim(),
    color: color.value,
    sort: Number(sortValue.value),
    enabled: enabled.value,
  }
  try {
    const saved = props.row
      ? await themesApi.update(props.row.id, payload)
      : await themesApi.create(payload)
    emit('saved', saved)
    emit('close')
  } catch (error) {
    applyError(error)
  } finally {
    saving.value = false
  }
}
</script>

<template>
  <Modal :open="open" :title="title" size="md" @close="emit('close')">
    <form class="theme-form" novalidate @submit.prevent="submit">
      <p v-if="summary" class="theme-form__summary" role="alert">{{ summary }}</p>

      <AppInput
        ref="nameRef"
        v-model="name"
        label="名称"
        required
        placeholder="2–20 字，如「安全」「交付质量」"
        :invalid="!!fieldErrors.name"
        :error="fieldErrors.name"
        hint="名称需全表唯一；停用后不可被新任务选择。"
      >
        <template #suffix>
          <span class="theme-form__count">{{ nameCount }}/{{ MAX_NAME }}</span>
        </template>
      </AppInput>

      <div class="theme-form__field">
        <p class="theme-form__label">颜色</p>
        <SourceColorSwatches v-model="color" :options="palette" />
        <p v-if="paletteLoading" class="theme-form__hint">正在加载色板…</p>
        <p v-if="fieldErrors.color" class="theme-form__error" role="alert">{{ fieldErrors.color }}</p>
        <p v-else class="theme-form__hint">主题色用于任务标签与甘特条；逾期/风险仍使用独立的语义色。</p>
      </div>

      <AppInput
        v-model="sortValue"
        label="排序"
        type="number"
        placeholder="0–99"
        :invalid="!!fieldErrors.sort"
        :error="fieldErrors.sort"
        hint="数字越小越靠前；同序时按创建顺序。"
      />

      <div class="theme-form__switch">
        <label class="switch">
          <input v-model="enabled" type="checkbox" class="switch__input" role="switch" />
          <span class="switch__track" aria-hidden="true"><span class="switch__dot" /></span>
          <span class="switch__label">{{ enabled ? '已启用' : '已停用' }}</span>
        </label>
        <p class="theme-form__hint">停用后不可被新任务选择，历史任务仍显示主题并标注「已停用」。</p>
      </div>
    </form>

    <template #footer>
      <AppButton variant="secondary" :disabled="saving" @click="emit('close')">取消</AppButton>
      <AppButton
        variant="primary"
        :loading="saving"
        :loading-text="saving ? '保存中…' : ''"
        :disabled="limitReached"
        @click="submit"
      >
        保存
      </AppButton>
    </template>
  </Modal>
</template>

<style scoped>
.theme-form {
  display: flex;
  flex-direction: column;
  gap: var(--sp-4);
}
.theme-form__summary {
  margin: 0;
  padding: var(--sp-2) var(--sp-3);
  border: 1px solid var(--c-danger-border);
  border-radius: var(--r-sm);
  background: var(--c-danger-soft);
  color: var(--c-danger-text);
  font-size: var(--fs-caption);
}
.theme-form__field {
  display: flex;
  flex-direction: column;
  gap: var(--sp-2);
}
.theme-form__label {
  margin: 0;
  font-size: var(--fs-body-sm);
  font-weight: 500;
  color: var(--c-ink-700);
}
.theme-form__count {
  font-size: var(--fs-micro);
  color: var(--c-ink-400);
  font-variant-numeric: tabular-nums;
}
.theme-form__hint {
  margin: 0;
  font-size: var(--fs-caption);
  line-height: var(--lh-caption);
  color: var(--c-ink-500);
}
.theme-form__error {
  margin: 0;
  font-size: var(--fs-caption);
  color: var(--c-danger-text);
}
.theme-form__switch {
  display: flex;
  flex-direction: column;
  gap: 6px;
}
.switch {
  display: inline-flex;
  align-items: center;
  gap: var(--sp-2);
  cursor: pointer;
}
.switch__input {
  position: absolute;
  opacity: 0;
  width: 1px;
  height: 1px;
}
.switch__track {
  position: relative;
  box-sizing: border-box;
  width: 36px;
  height: 20px;
  border-radius: 10px;
  background: var(--c-line);
  transition: background var(--t-fast) var(--ease);
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
  transition: transform var(--t-fast) var(--ease);
}
.switch__input:checked + .switch__track {
  background: var(--c-primary);
}
.switch__input:checked + .switch__track .switch__dot {
  transform: translateX(16px);
}
.switch__input:focus-visible + .switch__track {
  box-shadow: var(--focus-ring);
}
.switch__label {
  font-size: var(--fs-body-sm);
  color: var(--c-ink-700);
}
</style>
