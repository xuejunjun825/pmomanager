<script setup lang="ts">
/**
 * 催办按钮（DESIGN §4.5 / §9）：点击即反馈 —— loading → 「已催办 · 刚刚」并锁定 10 分钟。
 * 冷却窗口与后端同源判定：服务端 409 URGE_TOO_SOON 会把 lastUrgedAt 带回来，前端据此恢复禁用，
 * 所以刷新页面、换设备都不会绕过，也不会因为前端本地计时不准而误放行。
 */
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import AppButton from '@/components/ui/AppButton.vue'
import ChannelChip from '@/components/ui/ChannelChip.vue'
import { taskApi } from '@/api/tasks'
import { toApiError } from '@/api/http'
import { useToast } from '@/composables/useToast'
import { channelLabel } from '@/utils/format'
import type { ChannelStatus } from '@/types/enums'
import type { UrgeResult } from '@/types/api'

const props = withDefaults(
  defineProps<{
    taskId: number
    ownerName?: string
    lastUrgedAt?: string | null
    canUrge?: boolean
    disabledReason?: string
    urgeCount?: number
  }>(),
  { ownerName: '负责人', lastUrgedAt: null, canUrge: true, disabledReason: '', urgeCount: 0 },
)

const emit = defineEmits<{ urged: [result: UrgeResult] }>()

const toast = useToast()

/** 与后端窗口一致：同人同任务 10 分钟 */
const COOLDOWN_MS = 10 * 60 * 1000

const busy = ref(false)
const lastAt = ref<string | null>(props.lastUrgedAt ?? null)
const justUrged = ref(false)
const now = ref(Date.now())
/** 最近一次催办的渠道结果快照：常驻显示，钉钉未配置/未绑定时是中性灰而不是绿色成功 */
const channel = ref<{ status: ChannelStatus; label: string } | null>(null)

let ticker: number | undefined
onMounted(() => {
  ticker = window.setInterval(() => {
    now.value = Date.now()
  }, 30_000)
})
onBeforeUnmount(() => {
  if (ticker !== undefined) window.clearInterval(ticker)
})

watch(
  () => props.lastUrgedAt,
  (value) => {
    lastAt.value = value ?? null
    justUrged.value = false
  },
)

// 同一行换任务时清掉上一任务的渠道快照，避免 chip 串台
watch(
  () => props.taskId,
  () => {
    channel.value = null
  },
)

function timeOf(value: string | null): number {
  if (!value) return Number.NaN
  return Date.parse(value)
}

const cooldownUntil = computed(() => {
  const started = timeOf(lastAt.value)
  return Number.isFinite(started) ? started + COOLDOWN_MS : 0
})

const inCooldown = computed(() => cooldownUntil.value > now.value)

function hhmm(value: string | null): string {
  const parsed = timeOf(value)
  if (!Number.isFinite(parsed)) return ''
  const date = new Date(parsed)
  const hours = `${date.getHours()}`.padStart(2, '0')
  const minutes = `${date.getMinutes()}`.padStart(2, '0')
  return `${hours}:${minutes}`
}

const label = computed(() => {
  if (inCooldown.value) {
    return justUrged.value ? '已催办 · 刚刚' : `已于 ${hhmm(lastAt.value)} 催办过`
  }
  return '催办'
})

const disabled = computed(() => busy.value || inCooldown.value || !props.canUrge)

const title = computed(() => {
  if (inCooldown.value) {
    const until = hhmm(new Date(cooldownUntil.value).toISOString())
    return `10 分钟内不可重复催办，${until} 后可再次催办`
  }
  return props.canUrge ? undefined : props.disabledReason || '当前不可催办'
})

async function urge(): Promise<void> {
  if (disabled.value) return
  busy.value = true
  try {
    const result = await taskApi.urge(props.taskId)
    lastAt.value = result.lastUrgedAt ?? new Date().toISOString()
    justUrged.value = true
    now.value = Date.now()
    const text = result.channelLabel || channelLabel(result.channelStatus)
    channel.value = { status: result.channelStatus, label: text }
    // AC-21：只有真正走通钉钉才是成功；未配置/未绑定/站内降级都是中性提示，失败只用 warning
    if (result.channelStatus === 'SENT') {
      toast.success(result.message || `已通过钉钉提醒${props.ownerName}`)
    } else if (result.channelStatus === 'FAILED') {
      toast.warning(result.message || '钉钉发送失败，已保留站内提醒并将自动重试')
    } else {
      toast.info(result.message || `已发送站内提醒（${text}）`)
    }
    emit('urged', result)
  } catch (error) {
    const apiError = toApiError(error)
    if (apiError.code === 'URGE_TOO_SOON') {
      const last = apiError.details?.lastUrgedAt
      lastAt.value = typeof last === 'string' ? last : new Date().toISOString()
      justUrged.value = false
      now.value = Date.now()
      toast.warning(`10 分钟内已催办过${lastAt.value ? `（${hhmm(lastAt.value)}）` : ''}，稍后再试`)
    } else if (apiError.code === 'TASK_FINISHED' || apiError.status === 409) {
      toast.warning(apiError.message || '任务已结束，无需催办')
    } else {
      toast.error(apiError.message || '催办失败，请稍后重试')
    }
  } finally {
    busy.value = false
  }
}
</script>

<template>
  <div class="urge">
    <AppButton
      variant="secondary"
      :disabled="disabled"
      :loading="busy"
      loading-text="催办中"
      :title="title"
      :aria-label="inCooldown ? `${label}，10 分钟内不可重复催办` : '催办负责人'"
      @click="urge"
    >
      {{ label }}
    </AppButton>
    <span v-if="channel" class="urge__channel" role="status">
      <ChannelChip :status="channel.status" :label="channel.label" />
    </span>
    <span v-if="inCooldown" class="urge__note">10 分钟后可再次催办</span>
    <span v-else-if="!canUrge && disabledReason" class="urge__note">{{ disabledReason }}</span>
  </div>
</template>

<style scoped>
.urge {
  display: inline-flex;
  align-items: center;
  gap: var(--sp-2);
  flex-wrap: wrap;
}
.urge__channel {
  display: inline-flex;
}
.urge__note {
  font-size: var(--fs-caption);
  color: var(--c-ink-500);
  white-space: nowrap;
}
</style>
