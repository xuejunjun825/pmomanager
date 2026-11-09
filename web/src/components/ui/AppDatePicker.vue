<script setup lang="ts">
/** 日期选择（DESIGN §5.2）：原生 date 控件，值始终是 YYYY-MM-DD。 */
import { computed } from 'vue'

const props = withDefaults(
  defineProps<{
    modelValue?: string | null
    label?: string
    size?: 'compact' | 'default' | 'large'
    disabled?: boolean
    required?: boolean
    invalid?: boolean
    error?: string
    hint?: string
    min?: string
    max?: string
    id?: string
  }>(),
  { size: 'default', disabled: false, required: false, invalid: false },
)

const emit = defineEmits<{ 'update:modelValue': [value: string] }>()

const fallbackId = `date-${Math.random().toString(36).slice(2, 9)}`
const fieldId = computed(() => props.id || fallbackId)
</script>

<template>
  <div class="field" :class="`field--${size}`">
    <label v-if="label" class="field__label" :for="fieldId">
      {{ label }}<span v-if="required" class="field__req" aria-hidden="true">*</span>
    </label>
    <div class="field__control" :class="{ 'is-invalid': invalid || error, 'is-disabled': disabled }">
      <input
        :id="fieldId"
        class="field__input"
        type="date"
        :value="modelValue ?? ''"
        :disabled="disabled"
        :required="required"
        :min="min"
        :max="max"
        :aria-invalid="invalid || !!error || undefined"
        @input="emit('update:modelValue', ($event.target as HTMLInputElement).value)"
      />
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
  padding: 0 var(--sp-3);
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
.field__input {
  flex: 1;
  min-width: 0;
  height: 100%;
  border: 0;
  outline: none;
  background: transparent;
  color: var(--c-ink-900);
  font-family: inherit;
  font-size: var(--fs-body-sm);
}
.field__input::-webkit-calendar-picker-indicator {
  cursor: pointer;
  opacity: 0.55;
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
