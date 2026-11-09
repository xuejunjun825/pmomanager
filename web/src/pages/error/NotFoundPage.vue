<script setup lang="ts">
/**
 * 404（DESIGN §4.3）：既覆盖未知路径，也覆盖 owner 访问非本人任务时服务端返回 404 的情形，
 * 因此文案不暗示「越权」，只说内容不存在或不在可见范围。
 */
import { useRouter } from 'vue-router'
import AppButton from '@/components/ui/AppButton.vue'
import { useAuthStore } from '@/stores/auth'

const auth = useAuthStore()
const router = useRouter()

function home(): void {
  void router.replace(auth.isAuthenticated ? auth.landingPath() : '/login')
}
function back(): void {
  if (window.history.length > 1) router.back()
  else home()
}
</script>

<template>
  <div class="err">
    <div class="err__card">
      <p class="err__code" aria-hidden="true">404</p>
      <h1 class="err__title">页面不存在</h1>
      <p class="err__desc">链接可能已失效，或该内容不存在、不在你的可见范围内。</p>
      <div class="err__actions">
        <AppButton variant="primary" @click="home">返回工作台</AppButton>
        <AppButton variant="secondary" @click="back">返回上一页</AppButton>
      </div>
    </div>
  </div>
</template>

<style scoped>
.err {
  display: flex;
  min-height: 100vh;
  align-items: center;
  justify-content: center;
  padding: var(--sp-6);
  background: var(--c-bg);
}
.err__card {
  width: 440px;
  max-width: 100%;
  padding: var(--sp-10) var(--sp-8);
  background: var(--c-surface);
  border: 1px solid var(--c-line);
  border-radius: var(--r-xl);
  box-shadow: var(--sh-2);
  text-align: center;
}
.err__code {
  margin: 0;
  font-size: 44px;
  line-height: 1;
  font-weight: 700;
  letter-spacing: 0.04em;
  color: var(--c-primary-soft);
  -webkit-text-stroke: 1px var(--c-primary-border);
}
.err__title {
  margin: var(--sp-3) 0 0;
  font-size: var(--fs-h2);
  line-height: var(--lh-h2);
  font-weight: 600;
  color: var(--c-ink-900);
}
.err__desc {
  margin: var(--sp-2) 0 0;
  font-size: var(--fs-body-sm);
  line-height: var(--lh-body-sm);
  color: var(--c-ink-500);
}
.err__actions {
  display: flex;
  justify-content: center;
  gap: var(--sp-3);
  margin-top: var(--sp-6);
}
</style>
