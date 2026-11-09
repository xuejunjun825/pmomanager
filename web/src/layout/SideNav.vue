<script setup lang="ts">
/**
 * 左侧导航（DESIGN §3.1）：分组标题 11/600，条目 36 高，选中=浅底+主色字+3×16 竖条。
 * 折叠后只留图标，用 title 提供 tooltip（不做自绘浮层）。
 */
import { computed } from 'vue'
import { useRoute, RouterLink } from 'vue-router'
import { useAuthStore } from '@/stores/auth'
import { isNavActive, navGroupsFor, roleLabel, type NavIcon } from '@/utils/permissions'

const props = defineProps<{ collapsed: boolean; showToggle?: boolean }>()
const emit = defineEmits<{ toggle: [] }>()

const auth = useAuthStore()
const route = useRoute()

const groups = computed(() => navGroupsFor(auth.role))
const currentPath = computed(() => route.path)

const ICONS: Record<NavIcon, string[]> = {
  dashboard: ['M2.5 2.5h4v5h-4z', 'M9.5 2.5h4v3h-4z', 'M2.5 10.5h4v3h-4z', 'M9.5 8.5h4v5h-4z'],
  matrix: ['M2.5 3.5h11v9h-11z', 'M2.5 7.5h11', 'M6.2 3.5v9', 'M9.8 3.5v9'],
  tasks: ['M2.5 4.5h11', 'M2.5 8h11', 'M2.5 11.5h7'],
  my: ['M2.6 8.3l2.7 2.7 8-8'],
  meetings: ['M2.5 4.5h11v9h-11z', 'M2.5 7.5h11', 'M5.5 2.5v3', 'M10.5 2.5v3'],
  users: [
    'M6 7.6a2.3 2.3 0 1 0 0-4.6 2.3 2.3 0 0 0 0 4.6Z',
    'M1.8 13.2c.6-2.3 2.3-3.5 4.2-3.5s3.6 1.2 4.2 3.5',
    'M10.6 4.4a1.9 1.9 0 0 1 0 3.8',
    'M12 9.8c1.2.4 1.9 1.5 2.2 3.4',
  ],
  settings: ['M2.5 5.2h11', 'M2.5 10.8h11', 'M6.4 3.2v4', 'M9.8 8.8v4'],
}
</script>

<template>
  <aside class="nav" :class="{ 'nav--collapsed': props.collapsed }">
    <RouterLink to="/" class="nav__brand" :title="auth.siteName">
      <span class="nav__mark" aria-hidden="true">督</span>
      <span v-if="!props.collapsed" class="nav__brand-text">
        <span class="nav__brand-name">{{ auth.siteName }}</span>
        <span class="nav__brand-sub">会议任务督办</span>
      </span>
    </RouterLink>

    <nav class="nav__body" aria-label="主导航">
      <div v-for="group in groups" :key="group.key" class="nav__group">
        <p v-if="!props.collapsed" class="nav__group-title">{{ group.title }}</p>
        <span v-else class="nav__group-rule" aria-hidden="true" />
        <RouterLink
          v-for="item in group.items"
          :key="item.key"
          :to="item.path"
          class="nav__item"
          :class="{ 'is-active': isNavActive(item.path, currentPath) }"
          :title="props.collapsed ? item.label : undefined"
          :aria-current="isNavActive(item.path, currentPath) ? 'page' : undefined"
        >
          <svg class="nav__icon" width="16" height="16" viewBox="0 0 16 16" aria-hidden="true">
            <path
              v-for="(d, index) in ICONS[item.icon]"
              :key="index"
              :d="d"
              fill="none"
              stroke="currentColor"
              stroke-width="1.4"
              stroke-linecap="round"
              stroke-linejoin="round"
            />
          </svg>
          <span v-if="!props.collapsed" class="nav__label">{{ item.label }}</span>
        </RouterLink>
      </div>
    </nav>

    <div class="nav__foot">
      <button
        v-if="props.showToggle"
        type="button"
        class="nav__collapse"
        :aria-label="props.collapsed ? '展开侧栏' : '折叠侧栏'"
        :title="props.collapsed ? '展开侧栏' : '折叠侧栏'"
        @click="emit('toggle')"
      >
        <svg width="16" height="16" viewBox="0 0 16 16" aria-hidden="true">
          <path
            :d="props.collapsed ? 'M6 3.5 10.5 8 6 12.5' : 'M10 3.5 5.5 8 10 12.5'"
            fill="none"
            stroke="currentColor"
            stroke-width="1.5"
            stroke-linecap="round"
            stroke-linejoin="round"
          />
        </svg>
        <span v-if="!props.collapsed">折叠</span>
      </button>
      <p v-if="!props.collapsed" class="nav__role">{{ roleLabel(auth.role) }}</p>
    </div>
  </aside>
</template>

<style scoped>
.nav {
  position: sticky;
  top: 0;
  z-index: var(--z-nav);
  display: flex;
  flex-direction: column;
  width: var(--nav-w);
  height: 100vh;
  flex: none;
  background: var(--c-surface);
  border-right: 1px solid var(--c-line);
  transition: width var(--t-toggle) var(--ease);
  overflow: hidden;
}
.nav--collapsed {
  width: var(--nav-w-collapsed);
}
.nav__brand {
  display: flex;
  align-items: center;
  gap: var(--sp-2);
  height: var(--topbar-h);
  padding: 0 var(--sp-3);
  flex: none;
  text-decoration: none;
  border-bottom: 1px solid var(--c-line-soft);
}
.nav__mark {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 28px;
  height: 28px;
  flex: none;
  border-radius: var(--r-md);
  background: var(--c-primary);
  color: #fff;
  font-size: var(--fs-body-sm);
  font-weight: 700;
}
.nav__brand-text {
  display: flex;
  flex-direction: column;
  min-width: 0;
}
.nav__brand-name {
  font-size: var(--fs-body-sm);
  font-weight: 600;
  color: var(--c-ink-900);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}
.nav__brand-sub {
  font-size: var(--fs-micro);
  line-height: var(--lh-micro);
  color: var(--c-ink-400);
  white-space: nowrap;
}
.nav__body {
  flex: 1;
  overflow-y: auto;
  padding: var(--sp-3) var(--sp-2);
}
.nav__group + .nav__group {
  margin-top: var(--sp-4);
}
.nav__group-title {
  margin: 0 0 var(--sp-1);
  padding: 0 var(--sp-2);
  font-size: var(--fs-micro);
  line-height: var(--lh-micro);
  font-weight: 600;
  letter-spacing: 0.04em;
  color: var(--c-ink-400);
}
.nav__group-rule {
  display: block;
  height: 1px;
  margin: var(--sp-2) var(--sp-2);
  background: var(--c-line-soft);
}
.nav__group:first-child .nav__group-rule {
  display: none;
}
.nav__item {
  position: relative;
  display: flex;
  align-items: center;
  gap: 10px;
  height: 36px;
  padding: 0 var(--sp-2);
  border-radius: var(--r-md);
  color: var(--c-ink-600);
  font-size: var(--fs-body-sm);
  font-weight: 500;
  text-decoration: none;
  transition:
    background-color var(--t-hover) var(--ease),
    color var(--t-hover) var(--ease);
}
.nav__item + .nav__item {
  margin-top: 2px;
}
.nav__item:hover {
  background: var(--c-line-soft);
  color: var(--c-ink-900);
}
.nav__item.is-active {
  background: var(--c-primary-soft);
  color: var(--c-primary);
}
.nav__item.is-active::before {
  content: '';
  position: absolute;
  left: 0;
  top: 50%;
  width: 3px;
  height: 16px;
  margin-top: -8px;
  border-radius: 0 var(--r-pill) var(--r-pill) 0;
  background: var(--c-primary);
}
.nav__item:focus-visible {
  outline: none;
  box-shadow: var(--focus-ring);
}
.nav--collapsed .nav__item {
  justify-content: center;
  padding: 0;
}
.nav--collapsed .nav__brand {
  justify-content: center;
  padding: 0;
}
.nav__icon {
  flex: none;
}
.nav__label {
  white-space: nowrap;
}
.nav__foot {
  flex: none;
  padding: var(--sp-2);
  border-top: 1px solid var(--c-line-soft);
}
.nav__collapse {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: var(--sp-2);
  width: 100%;
  height: 32px;
  border: 1px solid transparent;
  border-radius: var(--r-md);
  background: transparent;
  color: var(--c-ink-500);
  font-family: inherit;
  font-size: var(--fs-caption);
  cursor: pointer;
  transition: background-color var(--t-hover) var(--ease);
}
.nav__collapse:hover {
  background: var(--c-line-soft);
  color: var(--c-ink-900);
}
.nav__collapse:focus-visible {
  outline: none;
  box-shadow: var(--focus-ring);
}
.nav__role {
  margin: var(--sp-1) 0 0;
  text-align: center;
  font-size: var(--fs-micro);
  color: var(--c-ink-400);
}
</style>
