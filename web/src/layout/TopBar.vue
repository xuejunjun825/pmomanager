<script setup lang="ts">
/**
 * 顶栏（DESIGN §3.2）：面包屑 + 通知铃铛（未读角标）+ 用户区。
 * 通知只在此处进入，侧栏不重复放一条。
 */
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useAuthStore } from '@/stores/auth'
import { useNotificationsStore } from '@/stores/notifications'
import { roleLabel } from '@/utils/permissions'
import { initialOf } from '@/utils/format'

const route = useRoute()
const router = useRouter()
const auth = useAuthStore()
const notifications = useNotificationsStore()

const menuOpen = ref(false)
const userRef = ref<HTMLElement | null>(null)

const crumbs = computed(() => {
  const section = route.meta.section as string | undefined
  const title = route.meta.title as string | undefined
  const items: string[] = []
  if (section) items.push(section)
  if (title) items.push(title)
  return items.length ? items : ['工作台']
})

const badge = computed(() => (notifications.unreadCount > 99 ? '99+' : String(notifications.unreadCount)))

function onDocumentClick(event: MouseEvent): void {
  if (!menuOpen.value) return
  const target = event.target as Node | null
  if (userRef.value && target && !userRef.value.contains(target)) menuOpen.value = false
}

function onKeydown(event: KeyboardEvent): void {
  if (event.key === 'Escape') menuOpen.value = false
}

onMounted(() => {
  document.addEventListener('click', onDocumentClick)
  document.addEventListener('keydown', onKeydown)
})
onBeforeUnmount(() => {
  document.removeEventListener('click', onDocumentClick)
  document.removeEventListener('keydown', onKeydown)
})

function goNotifications(): void {
  void router.push('/notifications')
}

async function logout(): Promise<void> {
  menuOpen.value = false
  notifications.reset()
  await auth.logout()
  await router.replace('/login')
}
</script>

<template>
  <header class="topbar">
    <nav class="topbar__crumbs" aria-label="位置">
      <template v-for="(crumb, index) in crumbs" :key="crumb">
        <span v-if="index > 0" class="topbar__sep" aria-hidden="true">/</span>
        <span class="topbar__crumb" :class="{ 'is-current': index === crumbs.length - 1 }">{{ crumb }}</span>
      </template>
    </nav>

    <div class="topbar__right">
      <button
        type="button"
        class="topbar__bell"
        aria-label="通知"
        :aria-describedby="notifications.unreadCount > 0 ? 'unread-badge' : undefined"
        @click="goNotifications"
      >
        <svg width="20" height="20" viewBox="0 0 20 20" aria-hidden="true">
          <path
            d="M10 3a4.6 4.6 0 0 0-4.6 4.6v3.1L4 13.2h12l-1.4-2.5V7.6A4.6 4.6 0 0 0 10 3Z"
            fill="none"
            stroke="currentColor"
            stroke-width="1.4"
            stroke-linejoin="round"
          />
          <path d="M8.2 15.4a1.9 1.9 0 0 0 3.6 0" fill="none" stroke="currentColor" stroke-width="1.4" stroke-linecap="round" />
        </svg>
        <span v-if="notifications.unreadCount > 0" id="unread-badge" class="topbar__badge">{{ badge }}</span>
      </button>

      <div ref="userRef" class="topbar__user">
        <button
          type="button"
          class="topbar__user-btn"
          :aria-expanded="menuOpen"
          aria-haspopup="menu"
          @click="menuOpen = !menuOpen"
        >
          <span class="topbar__avatar" aria-hidden="true">{{ initialOf(auth.user?.name) }}</span>
          <span class="topbar__user-text">
            <span class="topbar__name">{{ auth.user?.name ?? '未登录' }}</span>
            <span class="topbar__role">{{ roleLabel(auth.role) }}</span>
          </span>
          <svg class="topbar__chevron" width="12" height="12" viewBox="0 0 12 12" aria-hidden="true">
            <path d="M2.5 4.5 6 8l3.5-3.5" fill="none" stroke="currentColor" stroke-width="1.4" stroke-linecap="round" stroke-linejoin="round" />
          </svg>
        </button>
        <div v-if="menuOpen" class="topbar__menu" role="menu">
          <button type="button" class="topbar__menu-item" role="menuitem" @click="logout">退出登录</button>
        </div>
      </div>
    </div>
  </header>
</template>

<style scoped>
.topbar {
  position: sticky;
  top: 0;
  z-index: var(--z-nav);
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--sp-4);
  height: var(--topbar-h);
  padding: 0 var(--sp-6);
  background: var(--c-surface);
  border-bottom: 1px solid var(--c-line);
}
.topbar__crumbs {
  display: flex;
  align-items: center;
  gap: var(--sp-1);
  min-width: 0;
  font-size: var(--fs-body-sm);
  color: var(--c-ink-500);
}
.topbar__sep {
  color: var(--c-ink-400);
}
.topbar__crumb.is-current {
  font-weight: 600;
  color: var(--c-ink-900);
}
.topbar__right {
  display: flex;
  align-items: center;
  gap: var(--sp-2);
}
.topbar__bell {
  position: relative;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 36px;
  height: 36px;
  border: 0;
  border-radius: var(--r-md);
  background: transparent;
  color: var(--c-ink-600);
  cursor: pointer;
  transition: background-color var(--t-hover) var(--ease);
}
.topbar__bell:hover {
  background: var(--c-line-soft);
  color: var(--c-ink-900);
}
.topbar__bell:focus-visible {
  outline: none;
  box-shadow: var(--focus-ring);
}
.topbar__badge {
  position: absolute;
  top: 2px;
  right: 0;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  min-width: 18px;
  height: 18px;
  padding: 0 5px;
  border-radius: var(--r-pill);
  background: var(--c-primary);
  color: #fff;
  font-size: 10px;
  font-weight: 600;
  line-height: 1;
  font-variant-numeric: tabular-nums;
}
.topbar__user {
  position: relative;
}
.topbar__user-btn {
  display: flex;
  align-items: center;
  gap: var(--sp-2);
  height: 40px;
  padding: 0 var(--sp-2);
  border: 0;
  border-radius: var(--r-md);
  background: transparent;
  cursor: pointer;
  transition: background-color var(--t-hover) var(--ease);
}
.topbar__user-btn:hover {
  background: var(--c-line-soft);
}
.topbar__user-btn:focus-visible {
  outline: none;
  box-shadow: var(--focus-ring);
}
.topbar__avatar {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 28px;
  height: 28px;
  flex: none;
  border-radius: var(--r-pill);
  background: var(--c-primary-soft);
  border: 1px solid var(--c-primary-border);
  color: var(--c-primary);
  font-size: var(--fs-caption);
  font-weight: 600;
}
.topbar__user-text {
  display: flex;
  flex-direction: column;
  align-items: flex-start;
  line-height: 1.2;
}
.topbar__name {
  font-size: var(--fs-body-sm);
  font-weight: 500;
  color: var(--c-ink-900);
}
.topbar__role {
  font-size: var(--fs-micro);
  color: var(--c-ink-400);
}
.topbar__chevron {
  color: var(--c-ink-400);
}
.topbar__menu {
  position: absolute;
  top: calc(100% + 4px);
  right: 0;
  z-index: var(--z-dropdown);
  min-width: 132px;
  padding: var(--sp-1);
  background: var(--c-surface);
  border: 1px solid var(--c-line);
  border-radius: var(--r-lg);
  box-shadow: var(--sh-2);
}
.topbar__menu-item {
  display: block;
  width: 100%;
  height: 32px;
  padding: 0 var(--sp-2);
  border: 0;
  border-radius: var(--r-md);
  background: transparent;
  color: var(--c-ink-700);
  font-family: inherit;
  font-size: var(--fs-body-sm);
  text-align: left;
  cursor: pointer;
}
.topbar__menu-item:hover {
  background: var(--c-line-soft);
}
.topbar__menu-item:focus-visible {
  outline: none;
  box-shadow: var(--focus-ring);
}
@media (max-width: 640px) {
  .topbar {
    padding: 0 var(--sp-4);
  }
  .topbar__user-text {
    display: none;
  }
}
</style>
