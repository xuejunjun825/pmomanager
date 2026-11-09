<script setup lang="ts">
/**
 * 登录页（DESIGN §4.1）
 *
 * 两条入口的显隐完全由 /api/auth/config 的 dingtalkConfigured 决定：
 * 已配置 → 钉钉扫码为主按钮 + 分割线「或」+ 账号密码表单；
 * 未配置 → 钉钉整块不渲染（不占位），副标题改「使用账号密码登录」，底部一句灰字说明。
 * 密码登录始终可用，钉钉故障不能把任何人挡在门外（AC-21）。
 */
import { computed, onMounted, reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import AppButton from '@/components/ui/AppButton.vue'
import AppInput from '@/components/ui/AppInput.vue'
import { authApi } from '@/api/auth'
import { toApiError } from '@/api/http'
import { useAuthStore } from '@/stores/auth'
import { safeInternalPath } from '@/utils/permissions'

const auth = useAuthStore()
const route = useRoute()
const router = useRouter()

const form = reactive({ username: '', password: '' })
const passwordVisible = ref(false)
const submitting = ref(false)
const errorMessage = ref('')
const dingtalkLoading = ref(false)
const dingtalkError = ref('')

const dingtalkConfigured = computed(() => auth.dingtalkConfigured)
const subtitle = computed(() => (dingtalkConfigured.value ? '使用工作账号登录' : '使用账号密码登录'))

onMounted(() => {
  // 守卫已做过一次探测；这里兜住「直接打开登录页且配置尚未取到」的情况
  void auth.ensureLoaded()
})

function nextTarget(): string | null {
  return safeInternalPath(route.query.next)
}

async function submit(): Promise<void> {
  if (submitting.value) return
  errorMessage.value = ''
  if (!form.username.trim() || !form.password) {
    errorMessage.value = '请输入账号和密码'
    return
  }
  submitting.value = true
  try {
    await auth.login(form.username.trim(), form.password)
    await router.replace(nextTarget() ?? auth.landingPath())
  } catch (error) {
    const err = toApiError(error)
    // 统一文案：不区分「账号不存在」与「密码错误」，也不暴露账号是否被停用
    if (err.status === 401 || err.status === 400 || err.code === 'AUTH_BAD_CREDENTIALS') {
      errorMessage.value = '账号或密码不正确'
    } else if (err.isNetwork) {
      errorMessage.value = '无法连接服务器，请检查网络后重试'
    } else {
      errorMessage.value = err.message || '登录失败，请稍后重试'
    }
  } finally {
    submitting.value = false
  }
}

async function startDingTalk(): Promise<void> {
  if (dingtalkLoading.value) return
  dingtalkLoading.value = true
  dingtalkError.value = ''
  try {
    const { authorizeUrl } = await authApi.authorizeUrl(nextTarget() ?? undefined)
    if (!authorizeUrl) throw new Error('empty authorize url')
    window.location.assign(authorizeUrl)
  } catch (error) {
    const err = toApiError(error)
    // 中性提示而非红色报错：钉钉只是「暂不可用」，密码登录照常
    dingtalkError.value =
      err.code === 'DINGTALK_NOT_CONFIGURED'
        ? '钉钉登录尚未开通，请使用账号密码登录'
        : '钉钉登录暂时不可用，请稍后重试或使用账号密码登录'
    dingtalkLoading.value = false
  }
}
</script>

<template>
  <div class="login">
    <header class="login__brand">
      <span class="login__mark" aria-hidden="true">督</span>
      <span class="login__site">{{ auth.siteName || '会议任务督办系统' }}</span>
    </header>

    <main class="login__main">
      <section class="login__card" aria-labelledby="login-title">
        <div class="login__head">
          <h1 id="login-title" class="login__title">登录</h1>
          <p class="login__sub">{{ subtitle }}</p>
        </div>

        <div v-if="dingtalkConfigured" class="login__sso">
          <AppButton
            variant="primary"
            size="large"
            block
            class="login__dingtalk"
            :loading="dingtalkLoading"
            loading-text="正在跳转…"
            @click="startDingTalk"
          >
            <svg class="login__qr" viewBox="0 0 16 16" width="16" height="16" aria-hidden="true">
              <path
                d="M2 2h4v4H2zM10 2h4v4h-4zM2 10h4v4H2zM11.5 10H14v1.5h-2.5zM10 12.5h1.5V14H10zM12.5 12.5H14V14h-1.5z"
                fill="currentColor"
              />
            </svg>
            钉钉扫码登录
          </AppButton>
          <p v-if="dingtalkError" class="login__note" role="status">{{ dingtalkError }}</p>
          <div class="login__divider" aria-hidden="true"><span>或</span></div>
        </div>

        <form class="login__form" novalidate @submit.prevent="submit">
          <AppInput
            v-model="form.username"
            label="账号"
            size="large"
            placeholder="请输入账号"
            autocomplete="username"
            :readonly="submitting"
            @enter="submit"
          />
          <div class="login__password">
            <AppInput
              v-model="form.password"
              label="密码"
              size="large"
              :type="passwordVisible ? 'text' : 'password'"
              placeholder="请输入密码"
              autocomplete="current-password"
              :readonly="submitting"
              @enter="submit"
            >
              <template #suffix>
                <button
                  type="button"
                  class="login__eye"
                  :aria-label="passwordVisible ? '隐藏密码' : '显示密码'"
                  :aria-pressed="passwordVisible"
                  @click="passwordVisible = !passwordVisible"
                >
                  <svg viewBox="0 0 16 16" width="16" height="16" aria-hidden="true">
                    <path
                      v-if="!passwordVisible"
                      d="M8 3.2c3 0 5.4 2 6.5 4.8-1.1 2.8-3.5 4.8-6.5 4.8S2.6 10.8 1.5 8C2.6 5.2 5 3.2 8 3.2Zm0 1.3a3.5 3.5 0 1 0 0 7 3.5 3.5 0 0 0 0-7Zm0 1.4a2.1 2.1 0 1 1 0 4.2 2.1 2.1 0 0 1 0-4.2Z"
                      fill="currentColor"
                    />
                    <path
                      v-else
                      d="M2.3 2.3 13.7 13.7l-.9.9-2-2A7.6 7.6 0 0 1 8 12.8C5 12.8 2.6 10.8 1.5 8a8.6 8.6 0 0 1 2.3-2.9L1.4 3.2l.9-.9Zm2.4 3.6A7.1 7.1 0 0 0 2.8 8c1.1 2.3 3.1 3.5 5.2 3.5 1 0 2-.3 2.8-.8l-1.5-1.5A3.5 3.5 0 0 1 5.6 6l-.9-.1Zm3.3-2.7c3 0 5.4 2 6.5 4.8a9 9 0 0 1-1.8 2.5l-1-1a7 7 0 0 0 1.3-1.5C12.2 5.7 10.2 4.5 8 4.5c-.4 0-.8 0-1.2.1l-1-1c.7-.2 1.4-.4 2.2-.4Z"
                      fill="currentColor"
                    />
                  </svg>
                </button>
              </template>
            </AppInput>
          </div>

          <p v-if="errorMessage" class="login__error" role="alert" aria-live="assertive">
            <svg viewBox="0 0 16 16" width="14" height="14" aria-hidden="true">
              <path
                d="M8 1.5 15 14H1L8 1.5Zm0 3.7a.8.8 0 0 0-.8.8v3a.8.8 0 0 0 1.6 0v-3a.8.8 0 0 0-.8-.8Zm0 5.4a.9.9 0 1 0 0 1.8.9.9 0 0 0 0-1.8Z"
                fill="currentColor"
              />
            </svg>
            {{ errorMessage }}
          </p>

          <AppButton
            type="submit"
            variant="primary"
            size="large"
            block
            :loading="submitting"
            loading-text="登录中…"
          >
            登录
          </AppButton>
        </form>

        <p class="login__hint">忘记密码请联系管理员重置</p>
      </section>

      <p v-if="!dingtalkConfigured" class="login__foot">钉钉未配置，如需开通请联系管理员</p>
    </main>
  </div>
</template>

<style scoped>
.login {
  position: relative;
  display: flex;
  min-height: 100vh;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  padding: var(--sp-6);
  background:
    radial-gradient(120% 90% at 0% 0%, rgba(27, 77, 143, 0.07) 0%, rgba(27, 77, 143, 0) 55%),
    var(--c-bg);
}

.login__brand {
  position: fixed;
  top: var(--sp-6);
  left: var(--sp-6);
  display: flex;
  align-items: center;
  gap: var(--sp-2);
}
.login__mark {
  display: grid;
  width: 28px;
  height: 28px;
  place-items: center;
  border-radius: var(--r-lg);
  background: var(--c-primary);
  color: #fff;
  font-size: 14px;
  font-weight: 700;
  line-height: 1;
}
.login__site {
  font-size: var(--fs-body-sm);
  font-weight: 600;
  color: var(--c-ink-900);
}

.login__main {
  display: flex;
  width: 100%;
  flex-direction: column;
  align-items: center;
  gap: var(--sp-4);
}

.login__card {
  width: 420px;
  max-width: 100%;
  padding: var(--sp-8);
  background: var(--c-surface);
  border: 1px solid var(--c-line);
  border-radius: var(--r-xl);
  box-shadow: var(--sh-2);
}

.login__head {
  margin-bottom: var(--sp-6);
}
.login__title {
  margin: 0;
  font-size: var(--fs-h2);
  line-height: var(--lh-h2);
  font-weight: 600;
  color: var(--c-ink-900);
}
.login__sub {
  margin: var(--sp-1) 0 0;
  font-size: var(--fs-body-sm);
  line-height: var(--lh-body-sm);
  color: var(--c-ink-500);
}

.login__sso {
  display: flex;
  flex-direction: column;
  gap: var(--sp-3);
}
.login__dingtalk {
  background: var(--c-dingtalk);
}
.login__dingtalk:hover:not(:disabled) {
  background: var(--c-dingtalk-hover);
}
.login__qr {
  flex: none;
}
.login__note {
  margin: 0;
  font-size: var(--fs-caption);
  line-height: var(--lh-caption);
  color: var(--c-ink-500);
}

.login__divider {
  display: flex;
  align-items: center;
  gap: var(--sp-3);
  color: var(--c-ink-400);
  font-size: var(--fs-caption);
}
.login__divider::before,
.login__divider::after {
  content: '';
  height: 1px;
  flex: 1;
  background: var(--c-line);
}

.login__form {
  display: flex;
  flex-direction: column;
  gap: var(--sp-4);
}
.login__password {
  display: flex;
  flex-direction: column;
}
.login__eye {
  display: inline-flex;
  width: 40px;
  height: 40px;
  align-items: center;
  justify-content: center;
  margin-right: calc(-1 * var(--sp-3));
  padding: 0;
  border: 0;
  border-radius: 0 var(--r-md) var(--r-md) 0;
  background: transparent;
  color: var(--c-ink-400);
  cursor: pointer;
  transition: color var(--t-hover) var(--ease);
}
.login__eye:hover {
  color: var(--c-ink-700);
}
.login__eye:focus-visible {
  outline: none;
  box-shadow: var(--focus-ring);
}

.login__error {
  display: flex;
  align-items: flex-start;
  gap: var(--sp-2);
  margin: 0;
  padding: var(--sp-2) var(--sp-3);
  border: 1px solid var(--c-danger-border);
  border-radius: var(--r-md);
  background: var(--c-danger-soft);
  color: var(--c-danger-text);
  font-size: var(--fs-body-sm);
  line-height: var(--lh-body-sm);
}
.login__error svg {
  flex: none;
  margin-top: 2px;
}

.login__hint {
  margin: var(--sp-5) 0 0;
  text-align: center;
  font-size: var(--fs-caption);
  line-height: var(--lh-caption);
  color: var(--c-ink-400);
}
.login__foot {
  margin: 0;
  font-size: var(--fs-caption);
  line-height: var(--lh-caption);
  color: var(--c-ink-400);
}

@media (max-width: 480px) {
  .login {
    padding: var(--sp-4);
    justify-content: flex-start;
    padding-top: var(--sp-10);
  }
  .login__brand {
    position: static;
    margin-bottom: var(--sp-6);
  }
  .login__card {
    padding: var(--sp-5);
  }
}
</style>
