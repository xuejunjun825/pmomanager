<script setup lang="ts">
/**
 * 二次确认弹窗（DESIGN §1.3 / §4.12）：确认类动作统一走这里，避免各页自造模态。
 *
 * 约定：
 * - danger 的最终按钮是**实心红**——全站唯一允许实心红的地方，且只出现在这里；
 * - loading 期间禁止取消/关闭，防止「点了取消但请求已发出」的错觉；
 * - 默认落在确认按钮上，键盘用户回车即确认（Escape 仍可取消）。
 */
import { computed } from 'vue'
import AppButton from './AppButton.vue'
import Modal from './Modal.vue'

const props = withDefaults(
  defineProps<{
    open: boolean
    title: string
    description?: string
    confirmLabel?: string
    cancelLabel?: string
    tone?: 'default' | 'danger'
    loading?: boolean
    confirmDisabled?: boolean
  }>(),
  {
    description: '',
    confirmLabel: '确认',
    cancelLabel: '取消',
    tone: 'default',
    loading: false,
    confirmDisabled: false,
  },
)

const emit = defineEmits<{ confirm: []; close: [] }>()

const danger = computed(() => props.tone === 'danger')

function cancel(): void {
  if (props.loading) return
  emit('close')
}
</script>

<template>
  <Modal
    :open="open"
    :title="title"
    :description="description"
    size="sm"
    :closable="!loading"
    :close-on-overlay="!loading"
    @close="cancel"
  >
    <slot />
    <template #footer>
      <AppButton variant="secondary" :disabled="loading" @click="cancel">{{ cancelLabel }}</AppButton>
      <AppButton
        :variant="danger ? 'danger-outline' : 'primary'"
        :class="{ 'confirm__danger': danger }"
        :loading="loading"
        :disabled="confirmDisabled"
        @click="emit('confirm')"
      >
        {{ confirmLabel }}
      </AppButton>
    </template>
  </Modal>
</template>

<style scoped>
/* 实心红只在这里出现（DESIGN §1.3）；父级 scoped 样式可命中子组件根节点 */
.confirm__danger.btn {
  background: var(--c-danger);
  border-color: var(--c-danger);
  color: #fff;
}
.confirm__danger.btn:hover:not(:disabled) {
  background: #b42318;
  border-color: #b42318;
}
.confirm__danger.btn:active:not(:disabled) {
  background: #912018;
  border-color: #912018;
}
.confirm__danger.btn:focus-visible {
  outline: 2px solid var(--c-danger);
  outline-offset: 2px;
}
.confirm__danger.btn:disabled {
  background: var(--c-line-soft);
  border-color: var(--c-line);
  color: var(--c-ink-400);
}
</style>
