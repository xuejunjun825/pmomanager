<script setup lang="ts">
/**
 * 五步闭环条（DESIGN §3.6）：派发 → 进行 → 提交 → 验收 → 完成。
 *
 * - 只描述状态与「下一步」，不渲染任何操作按钮（按钮仍由 `TaskActions` 出，权限只由服务端决定）；
 * - `PENDING_ACCEPT` 时「提交」视为已完成、「验收」为当前步（状态机只有 4 个状态，步骤有 5 个）；
 * - `CANCELLED` 整条灰化、后续节点虚线，右侧给出中性「已取消」badge；
 * - 人名缺失退化为角色词（负责人 / 派发人），不显示 undefined。
 */
import { computed } from 'vue'
import AppBadge from '@/components/ui/AppBadge.vue'
import type { TaskStatus } from '@/types/enums'

const props = defineProps<{
  status: TaskStatus
  ownerName?: string
  dispatcherName?: string
}>()

const STEPS = ['派发', '进行', '提交', '验收', '完成'] as const

/** 状态 → 当前步下标；CANCELLED 无当前步（整条灰化） */
const CURRENT_INDEX: Record<TaskStatus, number> = {
  PENDING: 0,
  IN_PROGRESS: 1,
  PENDING_ACCEPT: 3,
  DONE: 4,
  CANCELLED: -1,
}

const cancelled = computed(() => props.status === 'CANCELLED')
const currentIndex = computed(() => CURRENT_INDEX[props.status] ?? 0)

function stateOf(index: number): 'done' | 'current' | 'future' {
  if (cancelled.value) return 'future'
  if (index < currentIndex.value) return 'done'
  if (index === currentIndex.value) return 'current'
  return 'future'
}

const ownerLabel = computed(() => props.ownerName?.trim() || '负责人')
const dispatcherLabel = computed(() => props.dispatcherName?.trim() || '派发人')

const nextText = computed(() => {
  switch (props.status) {
    case 'PENDING':
      return `下一步：负责人 ${ownerLabel.value}「开始处理」`
    case 'IN_PROGRESS':
      return `下一步：负责人 ${ownerLabel.value}「提交交付」`
    case 'PENDING_ACCEPT':
      return `下一步：派发人 ${dispatcherLabel.value}「验收通过 / 打回」`
    case 'DONE':
      return '已闭环，无待办操作'
    case 'CANCELLED':
      return '任务已取消，无待办操作'
    default:
      return ''
  }
})

/** 读屏整句：只报已完成与当前，不逐个念未来步骤 */
const ariaLabel = computed(() => {
  if (cancelled.value) return `任务进度：已取消（${STEPS.join('、')}）`
  const done = STEPS.slice(0, currentIndex.value)
  const current = STEPS[currentIndex.value]
  const parts = [`当前${current}`]
  if (done.length) parts.unshift(`已完成${done.join('、')}`)
  return `任务进度：${parts.join('，')}`
})
</script>

<template>
  <div class="flow" :class="{ 'is-cancelled': cancelled }">
    <div class="flow__head">
      <p class="flow__title">进度</p>
      <AppBadge v-if="cancelled" tone="neutral" dot>已取消</AppBadge>
    </div>

    <ol class="flow__track" :aria-label="ariaLabel">
      <li
        v-for="(step, index) in STEPS"
        :key="step"
        class="flow__step"
        :class="`is-${stateOf(index)}`"
      >
        <span class="flow__dot" aria-hidden="true">
          <svg v-if="stateOf(index) === 'done'" width="8" height="8" viewBox="0 0 8 8">
            <path d="M1.2 4.2 3.2 6.2 6.8 1.8" fill="none" stroke="currentColor" stroke-width="1.4" stroke-linecap="round" stroke-linejoin="round" />
          </svg>
        </span>
        <span class="flow__label">{{ step }}</span>
      </li>
    </ol>

    <p v-if="nextText" class="flow__next">{{ nextText }}</p>
  </div>
</template>

<style scoped>
.flow {
  display: flex;
  flex-direction: column;
  gap: 8px;
}
.flow__head {
  display: flex;
  align-items: center;
  gap: var(--sp-2);
}
.flow__title {
  margin: 0;
  font-size: var(--fs-caption);
  font-weight: 500;
  color: var(--c-ink-500);
  letter-spacing: 0.04em;
}
.flow__track {
  display: flex;
  align-items: flex-start;
  margin: 0;
  padding: 0;
  list-style: none;
}
.flow__step {
  display: flex;
  flex: 1 1 0;
  flex-direction: column;
  align-items: center;
  gap: 8px;
  min-width: 0;
  position: relative;
}
/* 连接线：从上一个节点中心连到本节点中心，画在圆的下半层 */
.flow__step + .flow__step::before {
  content: '';
  position: absolute;
  top: 8px;
  right: 50%;
  left: -50%;
  height: 2px;
  background: var(--c-line);
}
.flow__step + .flow__step.is-done::before {
  background: var(--c-success);
}
.flow__step + .flow__step.is-current::before {
  background: var(--c-success);
}
.flow__step + .flow__step.is-future::before {
  background: var(--c-line);
}
.flow.is-cancelled .flow__step + .flow__step::before {
  background: transparent;
  border-top: 1px dashed var(--c-line);
}
.flow__dot {
  position: relative;
  z-index: 1;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  box-sizing: border-box;
  width: 18px;
  height: 18px;
  border-radius: 50%;
  background: var(--c-surface);
  color: var(--c-surface);
}
.flow__step.is-done .flow__dot {
  background: var(--c-success);
  color: var(--c-surface);
}
.flow__step.is-current .flow__dot {
  background: var(--c-primary);
  box-shadow: 0 0 0 3px var(--c-primary-soft);
}
.flow__step.is-future .flow__dot {
  border: 1px solid var(--c-line);
  background: var(--c-surface);
}
.flow__step.is-current .flow__label {
  color: var(--c-ink-900);
  font-weight: 600;
}
.flow__step.is-done .flow__label {
  color: var(--c-ink-700);
}
.flow__label {
  font-size: var(--fs-caption);
  line-height: var(--lh-caption);
  color: var(--c-ink-400);
  white-space: nowrap;
}
.flow.is-cancelled .flow__dot {
  border: 1px dashed var(--c-line);
  background: var(--c-surface);
}
.flow.is-cancelled .flow__label {
  color: var(--c-ink-400);
}
.flow__next {
  margin: 0;
  font-size: var(--fs-micro);
  line-height: var(--lh-micro);
  color: var(--c-ink-500);
}
@media (max-width: 640px) {
  .flow__track {
    gap: 0;
  }
  .flow__step {
    gap: 4px;
    height: 44px;
    justify-content: flex-start;
  }
  .flow__dot {
    width: 16px;
    height: 16px;
  }
  .flow__step + .flow__step::before {
    top: 7px;
  }
  .flow__label {
    font-size: 11px;
  }
}
</style>
