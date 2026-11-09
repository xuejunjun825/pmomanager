<script setup lang="ts">
/**
 * 原因输入对话框（DESIGN §5.9）：打回 / 取消 / 代确认完成 / 改派 / 改期 共用。
 * 原因必填 2–200 字，确认按钮随语义变红；额外字段（改派的负责人、改期的新日期）由调用方放在 extra 插槽里。
 */
import { computed, nextTick, ref, watch } from 'vue'
import Modal from './Modal.vue'
import AppButton from './AppButton.vue'
import AppTextarea from './AppTextarea.vue'

const props = withDefaults(
  defineProps<{
    open: boolean
    title: string
    description?: string
    confirmLabel?: string
    variant?: 'danger' | 'primary'
    placeholder?: string
    minLength?: number
    maxLength?: number
    loading?: boolean
    /** 调用方校验额外字段失败时的错误文案 */
    error?: string
  }>(),
  {
    confirmLabel: '确认',
    variant: 'danger',
    placeholder: '写清楚原因，负责人在时间线里能看到',
    minLength: 2,
    maxLength: 200,
    loading: false,
  },
)

const emit = defineEmits<{ close: []; confirm: [reason: string] }>()

const reason = ref('')
const localError = ref('')
const fieldRef = ref<InstanceType<typeof AppTextarea> | null>(null)

watch(
  () => props.open,
  async (open) => {
    if (!open) return
    reason.value = ''
    localError.value = ''
    await nextTick()
    const el = fieldRef.value?.$el as HTMLElement | undefined
    el?.querySelector('textarea')?.focus()
  },
)

const trimmed = computed(() => reason.value.trim())
const counter = computed(() => `${trimmed.value.length}/${props.maxLength}`)

function submit(): void {
  if (props.loading) return
  const length = trimmed.value.length
  if (length < props.minLength) {
    localError.value = `请填写至少 ${props.minLength} 个字的原因`
    return
  }
  if (length > props.maxLength) {
    localError.value = `原因不能超过 ${props.maxLength} 个字`
    return
  }
  localError.value = ''
  emit('confirm', trimmed.value)
}

function onReason(value: string): void {
  reason.value = value
  if (localError.value) localError.value = ''
}
</script>

<template>
  <Modal
    :open="open"
    :title="title"
    :description="description"
    size="md"
    :close-on-overlay="!loading"
    :closable="!loading"
    @close="emit('close')"
  >
    <div class="reason">
      <slot name="extra" />
      <div class="reason__field" ref="fieldRoot">
        <AppTextarea
          ref="fieldRef"
          :model-value="reason"
          label="原因"
          required
          :rows="4"
          :maxlength="maxLength"
          :placeholder="placeholder"
          :invalid="Boolean(localError || error)"
          :error="localError || error"
          @update:model-value="onReason"
        />
        <span class="reason__counter num">{{ counter }}</span>
      </div>
    </div>
    <template #footer>
      <AppButton variant="secondary" :disabled="loading" @click="emit('close')">取消</AppButton>
      <AppButton
        :variant="variant === 'danger' ? 'danger-outline' : 'primary'"
        :loading="loading"
        loading-text="提交中"
        @click="submit"
      >
        {{ confirmLabel }}
      </AppButton>
    </template>
  </Modal>
</template>

<style scoped>
.reason {
  display: flex;
  flex-direction: column;
  gap: var(--sp-4);
}
.reason__field {
  position: relative;
}
.reason__counter {
  position: absolute;
  right: var(--sp-3);
  bottom: var(--sp-2);
  font-size: var(--fs-micro);
  color: var(--c-ink-400);
  pointer-events: none;
}
</style>
