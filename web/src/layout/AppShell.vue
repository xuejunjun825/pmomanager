<script setup lang="ts">
/**
 * 应用外壳（DESIGN §3）：左侧导航 224（折叠 64）+ 顶栏 56 + 内容区。
 * 默认插槽承载路由页面——只此一个 RouterView，避免嵌套重复渲染。
 */
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import { useAuthStore } from '@/stores/auth'
import { useUiStore } from '@/stores/ui'
import { useNotificationsStore } from '@/stores/notifications'
import SideNav from './SideNav.vue'
import TopBar from './TopBar.vue'
import ChangePasswordModal from '@/pages/ChangePasswordModal.vue'

const auth = useAuthStore()
const ui = useUiStore()
const notifications = useNotificationsStore()
const route = useRoute()

const narrow = ref(false)
const showPasswordModal = ref(false)
let media: MediaQueryList | null = null

function syncNarrow(event?: MediaQueryListEvent): void {
  narrow.value = event ? event.matches : (media?.matches ?? false)
}

onMounted(() => {
  media = window.matchMedia('(max-width: 1024px)')
  syncNarrow()
  media.addEventListener('change', syncNarrow)
  if (auth.isAuthenticated) void notifications.refreshUnread()
})

onBeforeUnmount(() => {
  media?.removeEventListener('change', syncNarrow)
})

const collapsed = computed(() => narrow.value || ui.sidebarCollapsed)

// 首次登录强制改密：不可关闭；改密成功后由 store 把标记置 false，模态自然消失
watch(
  () => auth.mustChangePassword,
  (must) => {
    showPasswordModal.value = must
  },
  { immediate: true },
)

// 切换路由刷新角标（催办/打回会新增通知）
watch(
  () => route.fullPath,
  () => {
    if (auth.isAuthenticated) void notifications.refreshUnread()
  },
)
</script>

<template>
  <div class="shell" :class="{ 'shell--narrow': narrow }">
    <SideNav :collapsed="collapsed" :show-toggle="!narrow" @toggle="ui.toggleSidebar()" />
    <div class="shell__main">
      <TopBar />
      <main class="shell__content" id="main-content">
        <div class="shell__inner">
          <slot />
        </div>
      </main>
    </div>
    <ChangePasswordModal v-model:open="showPasswordModal" :forced="auth.mustChangePassword" />
  </div>
</template>

<style scoped>
.shell {
  display: flex;
  min-height: 100vh;
  background: var(--c-bg);
}
.shell__main {
  flex: 1;
  min-width: 0;
  display: flex;
  flex-direction: column;
}
.shell__content {
  flex: 1;
  padding: var(--sp-6);
}
.shell__inner {
  max-width: var(--content-max);
  margin: 0 auto;
}
@media (max-width: 1024px) {
  .shell__content {
    padding: var(--sp-4);
  }
}
</style>
