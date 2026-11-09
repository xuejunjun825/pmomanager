<script setup lang="ts">
/** 下拉选择（DESIGN §5.2）：原生 select 保证键盘与移动端体验。 */
import { computed } from 'vue'

export interface SelectOption {
  label: string
  value: string | number
  disabled?: boolean
}

const props = withDefaults(
  defineProps<{
    modelValue?: string | number | null
    options: SelectOption[]
    label?: string
    placeholder?: string
    size?: 'compact' | 'default' | 'large'
    disabled?: boolean
    invalid?: boolean
    error?: string
    hint?: string
    id?: string
    required?: boolean
  }>(),
  { size: 'default', disabled: false, invalid: false, required: false },
)

const emit = defineEmits<{ 'update:modelValue': [value: string]; change: [value: string] }>()

const fallbackId = `sel-${Math.random().toString(36).slice(2, 9)}`
const fieldId = computed(() => props.id || fallbackId)

function onChange(event: Event): void {
  const value = (event.target as HTMLSelectElement).value
  emit('update:modelValue', value)
  emit('change', value)
}
</script>

<template>
  <div class="field" :class="`field--${size}`">
    <label v-if="label" class="field__label" :for="fieldId">
      {{ label }}<span v-if="required" class="field__req" aria-hidden="true">*</span>
    </label>
    <div class="field__control" :class="{ 'is-invalid': invalid || error, 'is-disabled': disabled }">
      <select
        :id="fieldId"
        class="field__select"
        :value="modelValue ?? ''"
        :disabled="disabled"
        :aria-invalid="invalid || !!error || undefined"
        @change="onChange"
      >
        <option v-if="placeholder" value="">{{ placeholder }}</option>
        <option v-for="option in options" :key="String(option.value)" :value="String(option.value)" :disabled="option.disabled">
          {{ option.label }}
        </option>
      </select>
      <svg class="field__chevron" width="12" height="12" viewBox="0 0 12 12" aria-hidden="true">
        <path d="M2.5 4.5 6 8l3.5-3.5" fill="none" stroke="currentColor" stroke-width="1.4" stroke-linecap="round" stroke-linejoin="round" />
      </svg>
    </div>
    <p v-if="error" class="field__error" role="alert">{{ error }}</p>
    <p v-else-if="hint" class="field__hint">{{ hint }}</p>
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
  display: flex;
  align-items: center;
  height: 32px;
  padding: 0 var(--sp-2) 0 var(--sp-3);
  background: var(--c-surface);
  border: 1px solid var(--c-line);
  border-radius: var(--r-md);
  transition:
    border-color var(--t-hover) var(--ease),
    box-shadow var(--t-hover) var(--ease);
}
.field--compact .field__control {
  height: 28px;
}
.field--large .field__control {
  height: 40px;
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
.field__select {
  flex: 1;
  min-width: 0;
  height: 100%;
  border: 0;
  outline: none;
  background: transparent;
  color: var(--c-ink-900);
  font-family: inherit;
  font-size: var(--fs-body-sm);
  appearance: none;
  cursor: pointer;
}
.field--large .field__select {
  font-size: var(--fs-body);
}
.field__select:disabled {
  cursor: not-allowed;
  color: var(--c-ink-500);
}
.field__chevron {
  color: var(--c-ink-400);
  flex: none;
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
</style>
