<script setup lang="ts">
/**
 * 500（DESIGN §4.3）：展示 traceId，让用户报障时能直接给出追踪号，
 * 而不是只说「打不开」。traceId 来自错误体或跳转参数。
 */
import { computed } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import AppButton from '@/components/ui/AppButton.vue'
import { useAuthStore } from '@/stores/auth'

const auth = useAuthStore()
const route = useRoute()
const router = useRouter()

const traceId = computed(() => (typeof route.query.traceId === 'string' ? route.query.traceId : ''))

function retry(): void {
  window.location.reload()
}
function home(): void {
  void router.replace(auth.isAuthenticated ? auth.landingPath() : '/login')
}
</script>

<template>
  <div class="err">
    <div class="err__card">
      <p class="err__code" aria-hidden="true">500</p>
      <h1 class="err__title">服务异常</h1>
      <p class="err__desc">请求没有完成。请稍后重试；若持续出现，请把追踪号一并发给管理员。</p>
      <p v-if="traceId" class="err__trace">
        追踪号 <code>{{ traceId }}</code>
      </p>
      <div class="err__actions">
        <AppButton variant="primary" @click="retry">重试</AppButton>
        <AppButton variant="secondary" @click="home">返回工作台</AppButton>
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
  width: 460px;
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
.err__trace {
  margin: var(--sp-3) 0 0;
  font-size: var(--fs-caption);
  line-height: var(--lh-caption);
  color: var(--c-ink-400);
}
.err__trace code {
  font-family: var(--font-num);
  color: var(--c-ink-700);
}
.err__actions {
  display: flex;
  justify-content: center;
  gap: var(--sp-3);
  margin-top: var(--sp-6);
}
</style>
