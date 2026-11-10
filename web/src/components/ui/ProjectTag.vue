<script setup lang="ts">
/**
 * 项目标签（DESIGN §1.2 / TECHNICAL §4.3）：中性主数据，不参与来源 8 色板。
 * 20 高 / 11px / 左右 6，与 `SourceTag` 同高同字号、不同色系；名称完整可读，
 * 超过 128px 省略并用 `title` 补全，停用只加文字后缀（不用 opacity 表达）。
 *
 * 「是否渲染、是否未指定」由 `projectView(source)` 决定，页面不得自行判断：
 * `<ProjectTag v-if="projectView(row.source)" v-bind="projectView(row.source) ?? {}" />`
 */
import { computed } from 'vue'
import { DISABLED_SUFFIX } from '@/utils/projectOptions'

const props = withDefaults(
  defineProps<{
    name?: string | null
    enabled?: boolean
    /** 改形历史：形态是「需绑定项目」但任务没有项目 */
    unspecified?: boolean
  }>(),
  { enabled: true, unspecified: false },
)

const label = computed(() => (props.unspecified ? '未指定项目' : (props.name ?? '').trim()))

/** 空名不渲染（绝不产生空标签）；父级通常已用 projectView 守卫 */
const visible = computed(() => props.unspecified || label.value.length > 0)

const showSuffix = computed(() => !props.unspecified && props.enabled === false)

const title = computed(() => {
  if (props.unspecified) return '未指定项目'
  return showSuffix.value ? `${label.value}${DISABLED_SUFFIX}` : label.value
})
</script>

<template>
  <span
    v-if="visible"
    class="tag"
    :class="{ 'tag--unspecified': unspecified, 'tag--disabled': showSuffix }"
    :title="title"
  >
    <span class="tag__text">{{ label }}</span>
    <span v-if="showSuffix" class="tag__suffix">{{ DISABLED_SUFFIX }}</span>
  </span>
</template>

<style scoped>
.tag {
  display: inline-flex;
  align-items: center;
  box-sizing: border-box;
  max-width: 128px;
  height: 20px;
  padding: 0 6px;
  border: 1px solid var(--c-line);
  border-radius: var(--r-sm);
  background: var(--c-line-soft);
  color: var(--c-ink-600);
  font-size: var(--fs-micro);
  line-height: var(--lh-micro);
  font-weight: 500;
  white-space: nowrap;
  vertical-align: middle;
}
.tag__text {
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.tag__suffix {
  flex: none;
  color: var(--c-ink-400);
}
.tag--unspecified {
  border-style: dashed;
  color: var(--c-ink-500);
}
</style>
