<script setup lang="ts">
/**
 * 应用外壳：只有「已登录 + 非公开页」才套 AppShell（侧栏/顶栏/内容区），
 * 登录页、钉钉回调与异常页保持整屏独立——否则未登录时会看到一个空的侧栏。
 * ToastHost 全局挂一次，任何页面（含登录页）都能提示。
 */
import { computed } from 'vue'
import { RouterView, useRoute } from 'vue-router'
import ToastHost from '@/components/ui/ToastHost.vue'
import AppShell from '@/layout/AppShell.vue'
import { useAuthStore } from '@/stores/auth'

const auth = useAuthStore()
const route = useRoute()

const withShell = computed(() => auth.isAuthenticated && route.meta.public !== true)
</script>

<template>
  <AppShell v-if="withShell">
    <RouterView />
  </AppShell>
  <RouterView v-else />
  <ToastHost />
</template>
