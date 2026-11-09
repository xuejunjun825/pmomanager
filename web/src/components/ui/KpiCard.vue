<script setup lang="ts">
/**
 * 看板 KPI 卡（DESIGN §5 / §4.9）：8px 圆角 + 1px 边框、无阴影，最小宽 168。
 * 红色是配额资源：只有逾期数 > 0 时页面才传 danger，值变红并出现 3px 左侧红条。
 */
withDefaults(
  defineProps<{
    label: string
    /** 已格式化好的数字/比率（千分位、百分号由调用方决定，卡片不猜语义） */
    value: string
    sub?: string
    /** 逾期类指标：>0 时红色 + 左侧 3px 红条（DESIGN §1.3） */
    danger?: boolean
  }>(),
  { sub: '', danger: false },
)
</script>

<template>
  <div class="kpi" :class="{ 'kpi--danger': danger }">
    <span class="kpi__label">{{ label }}</span>
    <span class="kpi__value num" :class="{ 'kpi__value--danger': danger }">{{ value }}</span>
    <span v-if="sub" class="kpi__sub">{{ sub }}</span>
  </div>
</template>

<style scoped>
.kpi {
  display: flex;
  flex-direction: column;
  gap: var(--sp-1);
  min-width: 168px;
  padding: var(--sp-5);
  border: 1px solid var(--c-line);
  border-radius: var(--r-lg);
  background: var(--c-surface);
  position: relative;
}
/* 逾期红条用 inset 阴影，不占布局宽度，卡片之间仍然对齐 */
.kpi--danger {
  box-shadow: inset 3px 0 0 var(--c-danger);
}
.kpi__label {
  font-size: var(--fs-body-sm);
  line-height: var(--lh-body-sm);
  color: var(--c-ink-600);
}
.kpi__value {
  font-size: clamp(26px, 2.2vw, 32px);
  line-height: 1.25;
  font-weight: 600;
  color: var(--c-ink-900);
  font-variant-numeric: tabular-nums;
}
.kpi__value--danger {
  color: var(--c-danger-text);
}
.kpi__sub {
  font-size: var(--fs-caption);
  line-height: var(--lh-caption);
  color: var(--c-ink-500);
}
</style>
