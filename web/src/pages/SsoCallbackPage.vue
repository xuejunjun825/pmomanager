<script setup lang="ts">
/**
 * 钉钉登录回调中转页（DESIGN §4.1 / TECHNICAL §6.1）
 *
 * 服务端在 /api/auth/dingtalk/callback 完成 code→unionId→建档并建立会话后，
 * 302 到本页并带上 ?next=。本页只做一件事：确认会话已建立，然后按 next 或角色落地页跳转。
 * 处于钉钉容器内时（?authCode=），改用一次性免登码换取会话。
 */
import { onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import AppButton from '@/components/ui/AppButton.vue'
import { authApi } from '@/api/auth'
import { useAuthStore } from '@/stores/auth'
import { safeInternalPath } from '@/utils/permissions'

const auth = useAuthStore()
const route = useRoute()
const router = useRouter()

const state = ref<'loading' | 'error'>('loading')
const message = ref('正在完成钉钉登录…')

onMounted(async () => {
  const authCode = typeof route.query.authCode === 'string' ? route.query.authCode : ''
  const next = safeInternalPath(route.query.next)

  try {
    if (authCode) {
      await authApi.dingtalkAuto({ authCode, next: next ?? undefined })
      await auth.ensureLoaded(true)
    } else {
      await auth.ensureLoaded(true)
    }

    if (!auth.isAuthenticated) {
      state.value = 'error'
      message.value = '钉钉登录未完成，请重新登录'
      return
    }

    await router.replace(next ?? auth.landingPath())
  } catch {
    state.value = 'error'
    message.value = '钉钉登录暂时不可用，请使用账号密码登录'
  }
})

function toLogin(): void {
  const next = safeInternalPath(route.query.next)
  void router.replace({ path: '/login', query: next ? { next } : {} })
}
</script>

<template>
  <div class="sso">
    <div class="sso__card" role="status" aria-live="polite">
      <template v-if="state === 'loading'">
        <span class="sso__spinner" aria-hidden="true" />
        <p class="sso__title">{{ message }}</p>
        <p class="sso__sub">请稍候，正在为你建立登录状态…</p>
      </template>

      <template v-else>
        <span class="sso__warn" aria-hidden="true">
          <svg viewBox="0 0 16 16" width="20" height="20">
            <path
              d="M8 1.5 15 14H1L8 1.5Zm0 3.7a.8.8 0 0 0-.8.8v3a.8.8 0 0 0 1.6 0v-3a.8.8 0 0 0-.8-.8Zm0 5.4a.9.9 0 1 0 0 1.8.9.9 0 0 0 0-1.8Z"
              fill="currentColor"
            />
          </svg>
        </span>
        <p class="sso__title">{{ message }}</p>
        <p class="sso__sub">账号密码登录始终可用，不受钉钉配置影响。</p>
        <AppButton variant="primary" @click="toLogin">返回登录</AppButton>
      </template>
    </div>
  </div>
</template>

<style scoped>
.sso {
  display: flex;
  min-height: 100vh;
  align-items: center;
  justify-content: center;
  padding: var(--sp-6);
  background: var(--c-bg);
}
.sso__card {
  display: flex;
  width: 360px;
  max-width: 100%;
  flex-direction: column;
  align-items: center;
  gap: var(--sp-2);
  padding: var(--sp-8);
  background: var(--c-surface);
  border: 1px solid var(--c-line);
  border-radius: var(--r-xl);
  box-shadow: var(--sh-2);
  text-align: center;
}
.sso__spinner {
  width: 20px;
  height: 20px;
  margin-bottom: var(--sp-2);
  border: 2px solid var(--c-primary-soft);
  border-top-color: var(--c-primary);
  border-radius: 50%;
  animation: sso-spin 720ms linear infinite;
}
.sso__warn {
  color: var(--c-warn);
  margin-bottom: var(--sp-1);
}
.sso__title {
  margin: 0;
  font-size: var(--fs-h3);
  line-height: var(--lh-h3);
  font-weight: 600;
  color: var(--c-ink-900);
}
.sso__sub {
  margin: 0 0 var(--sp-4);
  font-size: var(--fs-body-sm);
  line-height: var(--lh-body-sm);
  color: var(--c-ink-500);
}
@keyframes sso-spin {
  to {
    transform: rotate(360deg);
  }
}
@media (prefers-reduced-motion: reduce) {
  .sso__spinner {
    animation-duration: 1800ms;
  }
}
</style>
