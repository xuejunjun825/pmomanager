<script setup lang="ts">
/** 多行文本（DESIGN §5.2）：说明/原因输入，默认 3 行。 */
import { computed } from 'vue'

const props = withDefaults(
  defineProps<{
    modelValue?: string | null
    label?: string
    placeholder?: string
    rows?: number
    maxlength?: number
    disabled?: boolean
    readonly?: boolean
    required?: boolean
    invalid?: boolean
    error?: string
    hint?: string
    id?: string
  }>(),
  { rows: 3, disabled: false, readonly: false, required: false, invalid: false },
)

const emit = defineEmits<{ 'update:modelValue': [value: string] }>()

const fallbackId = `ta-${Math.random().toString(36).slice(2, 9)}`
const fieldId = computed(() => props.id || fallbackId)
const length = computed(() => (props.modelValue ?? '').length)
</script>

<template>
  <div class="field">
    <label v-if="label" class="field__label" :for="fieldId">
      {{ label }}<span v-if="required" class="field__req" aria-hidden="true">*</span>
    </label>
    <div class="field__control" :class="{ 'is-invalid': invalid || error, 'is-disabled': disabled }">
      <textarea
        :id="fieldId"
        class="field__textarea"
        :rows="rows"
        :value="modelValue ?? ''"
        :placeholder="placeholder"
        :maxlength="maxlength"
        :disabled="disabled"
        :readonly="readonly"
        :required="required"
        :aria-invalid="invalid || !!error || undefined"
        @input="emit('update:modelValue', ($event.target as HTMLTextAreaElement).value)"
      />
    </div>
    <div class="field__meta">
      <p v-if="error" class="field__error" role="alert">{{ error }}</p>
      <p v-else-if="hint" class="field__hint">{{ hint }}</p>
      <span v-else />
      <span v-if="maxlength" class="field__count">{{ length }}/{{ maxlength }}</span>
    </div>
  </div>
</template>

<style scoped>
.field {
  display: flex;
  flex-direction: column;
  gap: var(--sp-1);
  min-width: 0;
}
.field__label {
  font-size: var(--fs-body-sm);
  font-weight: 500;
  color: var(--c-ink-700);
  line-height: var(--lh-body-sm);
}
.field__req {
  color: var(--c-danger);
  margin-left: 2px;
}
.field__control {
  padding: var(--sp-2) var(--sp-3);
  background: var(--c-surface);
  border: 1px solid var(--c-line);
  border-radius: var(--r-md);
  transition:
    border-color var(--t-hover) var(--ease),
    box-shadow var(--t-hover) var(--ease);
}
.field__control:hover:not(.is-disabled) {
  border-color: var(--c-ink-400);
}
.field__control:focus-within {
  border-color: var(--c-primary);
  box-shadow: var(--focus-ring);
}
.field__control.is-invalid {
  border-color: var(--c-danger);
}
.field__control.is-disabled {
  background: var(--c-line-soft);
}
.field__textarea {
  display: block;
  width: 100%;
  border: 0;
  outline: none;
  resize: vertical;
  background: transparent;
  color: var(--c-ink-900);
  font-family: inherit;
  font-size: var(--fs-body-sm);
  line-height: var(--lh-body-sm);
}
.field__textarea::placeholder {
  color: var(--c-ink-400);
}
.field__meta {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: var(--sp-2);
  min-height: 16px;
}
.field__error {
  margin: 0;
  font-size: var(--fs-caption);
  line-height: var(--lh-caption);
  color: var(--c-danger-text);
}
.field__hint {
  margin: 0;
  font-size: var(--fs-caption);
  line-height: var(--lh-caption);
  color: var(--c-ink-500);
}
.field__count {
  margin-left: auto;
  font-size: var(--fs-micro);
  line-height: var(--lh-micro);
  color: var(--c-ink-400);
  font-variant-numeric: tabular-nums;
}
</style>
