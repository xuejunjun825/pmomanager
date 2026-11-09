<script setup lang="ts">
/**
 * 503（DESIGN §4.3 / AC-21）：钉钉未配置或外部服务不可用时的落地页。
 * 语气必须中性——这不是故障红灯，站内功能与账号密码登录都照常可用。
 */
import { useRouter } from 'vue-router'
import AppButton from '@/components/ui/AppButton.vue'

const router = useRouter()

function retry(): void {
  window.location.reload()
}
function toLogin(): void {
  void router.replace('/login')
}
</script>

<template>
  <div class="err">
    <div class="err__card">
      <p class="err__code" aria-hidden="true">503</p>
      <h1 class="err__title">服务暂不可用</h1>
      <p class="err__desc">
        该功能依赖的外部服务当前不可用（例如钉钉尚未配置或暂时无法连接）。站内任务、会议与账号密码登录不受影响。
      </p>
      <div class="err__actions">
        <AppButton variant="primary" @click="toLogin">使用账号密码登录</AppButton>
        <AppButton variant="secondary" @click="retry">重新加载</AppButton>
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
.err__actions {
  display: flex;
  justify-content: center;
  gap: var(--sp-3);
  margin-top: var(--sp-6);
}
</style>
