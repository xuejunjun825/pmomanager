<script setup lang="ts">
/**
 * 输入框（DESIGN §5.2）：默认 32 高，登录等表单用 large(40)。
 * 错误态用「红边框 + 行内文案」，不弹 Toast（422 回填走这里）。
 */
import { computed, ref } from 'vue'

const props = withDefaults(
  defineProps<{
    modelValue?: string | number | null
    label?: string
    placeholder?: string
    type?: 'text' | 'password' | 'email' | 'search' | 'number'
    size?: 'compact' | 'default' | 'large'
    disabled?: boolean
    readonly?: boolean
    required?: boolean
    invalid?: boolean
    error?: string
    hint?: string
    id?: string
    name?: string
    autocomplete?: string
    maxlength?: number
    inputmode?: 'text' | 'numeric' | 'decimal' | 'tel' | 'email' | 'search'
  }>(),
  { type: 'text', size: 'default', disabled: false, readonly: false, required: false, invalid: false },
)

const emit = defineEmits<{ 'update:modelValue': [value: string]; blur: [event: FocusEvent]; enter: [] }>()

const inputRef = ref<HTMLInputElement | null>(null)
const fallbackId = `inp-${Math.random().toString(36).slice(2, 9)}`
const fieldId = computed(() => props.id || fallbackId)
const describedBy = computed(() => {
  const ids: string[] = []
  if (props.error) ids.push(`${fieldId.value}-error`)
  else if (props.hint) ids.push(`${fieldId.value}-hint`)
  return ids.length ? ids.join(' ') : undefined
})

function onInput(event: Event): void {
  emit('update:modelValue', (event.target as HTMLInputElement).value)
}

defineExpose({ focus: () => inputRef.value?.focus() })
</script>

<template>
  <div class="field" :class="`field--${size}`">
    <label v-if="label" class="field__label" :for="fieldId">
      {{ label }}<span v-if="required" class="field__req" aria-hidden="true">*</span>
    </label>
    <div class="field__control" :class="{ 'is-invalid': invalid || error, 'is-disabled': disabled }">
      <span v-if="$slots.prefix" class="field__affix"><slot name="prefix" /></span>
      <input
        :id="fieldId"
        ref="inputRef"
        class="field__input"
        :type="type"
        :name="name"
        :value="modelValue ?? ''"
        :placeholder="placeholder"
        :disabled="disabled"
        :readonly="readonly"
        :required="required"
        :autocomplete="autocomplete"
        :maxlength="maxlength"
        :inputmode="inputmode"
        :aria-invalid="invalid || !!error || undefined"
        :aria-describedby="describedBy"
        @input="onInput"
        @blur="emit('blur', $event)"
        @keyup.enter="emit('enter')"
      />
      <span v-if="$slots.suffix" class="field__affix"><slot name="suffix" /></span>
    </div>
    <p v-if="error" :id="`${fieldId}-error`" class="field__error" role="alert">{{ error }}</p>
    <p v-else-if="hint" :id="`${fieldId}-hint`" class="field__hint">{{ hint }}</p>
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
  gap: var(--sp-2);
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
  padding: 0 var(--sp-3);
  border-radius: var(--r-md);
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
.field__control.is-invalid:focus-within {
  box-shadow: 0 0 0 3px rgba(217, 45, 32, 0.15);
}
.field__control.is-disabled {
  background: var(--c-line-soft);
  cursor: not-allowed;
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
.field--large .field__input {
  font-size: var(--fs-body);
}
.field__input::placeholder {
  color: var(--c-ink-400);
}
.field__input:disabled {
  color: var(--c-ink-500);
  cursor: not-allowed;
}
.field__affix {
  display: inline-flex;
  align-items: center;
  color: var(--c-ink-400);
  font-size: var(--fs-body-sm);
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
