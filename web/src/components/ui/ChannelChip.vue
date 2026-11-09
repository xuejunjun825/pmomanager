<script setup lang="ts">
/**
 * 渠道状态 chip（DESIGN §4.10）：20 高 / 11·16，五态文案固定。
 *
 * 语义边界：
 * - 文案优先取服务端 channelLabel（唯一真源在服务端），前端只用 CHANNEL_LABELS 兜底；
 * - 只有「钉钉失败」可点（查看重试说明），其余为纯展示，避免把状态做成假按钮；
 * - 「钉钉未配置」「未绑定钉钉」一律中性灰——未配置是部署事实，不是错误（AC-21 不红色告警）。
 */
import { computed } from 'vue'
import { CHANNEL_LABELS, type ChannelStatus } from '@/types/enums'

const props = withDefaults(
  defineProps<{
    status: ChannelStatus
    /** 服务端返回的 channelLabel，缺省回落本地文案 */
    label?: string | null
    /** 允许「钉钉失败」变为可点（打开重试说明）；其它状态永远不可点 */
    interactive?: boolean
  }>(),
  { label: null, interactive: false },
)

const emit = defineEmits<{ select: [status: ChannelStatus] }>()

const text = computed(() => props.label || CHANNEL_LABELS[props.status] || CHANNEL_LABELS.SKIPPED)

/** 五态归三色：送达=成功，失败=危险，其余（含未配置/未绑定/站内/待发送）=中性 */
const tone = computed<'success' | 'danger' | 'neutral'>(() => {
  if (props.status === 'SENT') return 'success'
  if (props.status === 'FAILED') return 'danger'
  return 'neutral'
})

const clickable = computed(() => props.interactive && props.status === 'FAILED')

const hint = computed(() => {
  if (props.status === 'NOT_CONFIGURED') return '钉钉应用尚未配置，本条仅站内可见'
  if (props.status === 'UNBOUND') return '该用户未绑定钉钉，本条仅站内可见'
  if (props.status === 'FAILED') return '钉钉推送失败，站内通知照常送达'
  if (props.status === 'PENDING') return '钉钉推送排队中'
  if (props.status === 'SENT') return '已通过钉钉工作通知送达'
  return '站内通知'
})
</script>

<template>
  <button
    v-if="clickable"
    type="button"
    class="chip"
    :class="[`chip--${tone}`, 'chip--interactive']"
    :title="hint"
    :aria-label="`${text}，查看推送重试说明`"
    @click.stop="emit('select', status)"
  >
    <span class="chip__dot" aria-hidden="true" />
    {{ text }}
  </button>
  <span v-else class="chip" :class="`chip--${tone}`" :title="hint">
    <span class="chip__dot" aria-hidden="true" />
    {{ text }}
  </span>
</template>

<style scoped>
.chip {
  display: inline-flex;
  align-items: center;
  gap: 5px;
  height: 20px;
  padding: 0 6px;
  border: 1px solid var(--c-line);
  border-radius: var(--r-sm);
  background: var(--c-surface-hover);
  color: var(--c-ink-600);
  font-family: inherit;
  font-size: var(--fs-micro);
  line-height: 16px;
  white-space: nowrap;
  vertical-align: middle;
}
.chip__dot {
  width: 5px;
  height: 5px;
  border-radius: 50%;
  background: currentColor;
  opacity: 0.7;
  flex: none;
}
.chip--success {
  background: var(--c-success-soft);
  border-color: var(--c-success-border);
  color: var(--c-success);
}
.chip--danger {
  background: var(--c-danger-soft);
  border-color: var(--c-danger-border);
  color: var(--c-danger-text);
}
.chip--neutral {
  background: var(--c-line-soft);
  border-color: var(--c-line);
  color: var(--c-ink-600);
}
.chip--interactive {
  cursor: pointer;
  transition:
    background var(--t-hover) var(--ease),
    border-color var(--t-hover) var(--ease);
}
.chip--interactive:hover {
  background: var(--c-danger-soft);
  border-color: var(--c-danger);
}
.chip--interactive:active {
  transform: translateY(0.5px);
}
</style>
