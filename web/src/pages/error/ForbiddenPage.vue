<script setup lang="ts">
/**
 * 403（DESIGN §4.3）：越权页面在进入前就被守卫拦住，这里只解释原因并给出可行出口。
 * 说明里点出当前角色——用户常常不知道自己被分到了哪个角色。
 */
import { computed } from 'vue'
import { useRouter } from 'vue-router'
import AppButton from '@/components/ui/AppButton.vue'
import { useAuthStore } from '@/stores/auth'
import { roleLabel } from '@/utils/permissions'

const auth = useAuthStore()
const router = useRouter()

const role = computed(() => (auth.role ? roleLabel(auth.role) : '未登录'))

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
      <p class="err__code" aria-hidden="true">403</p>
      <h1 class="err__title">无访问权限</h1>
      <p class="err__desc">
        当前角色（{{ role }}）没有该页面的访问权限。如需开通，请联系管理员调整角色。
      </p>
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
