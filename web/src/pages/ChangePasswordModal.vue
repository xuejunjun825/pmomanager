<script setup lang="ts">
/**
 * 修改密码模态（DESIGN §4.2）
 *
 * forced=true（首次登录）时不可关闭：没有 ×、Esc 与遮罩都不生效，
 * 唯一出口是把初始密码改掉；改密成功后 store 的 mustChangePassword 变 false，模态自然消失。
 * 强度规则以服务端 422 AUTH_PASSWORD_WEAK 为准，前端只做即时提示与一致性校验。
 */
import { computed, reactive, ref, watch } from 'vue'
import AppButton from '@/components/ui/AppButton.vue'
import AppInput from '@/components/ui/AppInput.vue'
import Modal from '@/components/ui/Modal.vue'
import { toApiError } from '@/api/http'
import { useToast } from '@/composables/useToast'
import { useAuthStore } from '@/stores/auth'

const props = withDefaults(defineProps<{ open: boolean; forced?: boolean }>(), { forced: false })
const emit = defineEmits<{ 'update:open': [value: boolean]; changed: [] }>()

const auth = useAuthStore()
const toast = useToast()

const form = reactive({ oldPassword: '', newPassword: '', confirmPassword: '' })
const errors = reactive({ oldPassword: '', newPassword: '', confirmPassword: '', form: '' })
const submitting = ref(false)

const title = computed(() => (props.forced ? '请先修改初始密码' : '修改密码'))
const description = computed(() =>
  props.forced ? '为了账号安全，首次登录需要设置新密码后才能继续使用系统。' : '修改成功后，密码立即生效。',
)

watch(
  () => props.open,
  (open) => {
    if (!open) return
    form.oldPassword = ''
    form.newPassword = ''
    form.confirmPassword = ''
    clearErrors()
  },
)

function clearErrors(): void {
  errors.oldPassword = ''
  errors.newPassword = ''
  errors.confirmPassword = ''
  errors.form = ''
}

function close(): void {
  if (props.forced || submitting.value) return
  emit('update:open', false)
}

function validate(): boolean {
  clearErrors()
  if (!form.oldPassword) errors.oldPassword = '请输入当前密码'
  if (!form.newPassword) {
    errors.newPassword = '请输入新密码'
  } else if (form.newPassword.length < 8) {
    errors.newPassword = '新密码至少 8 位，建议包含字母与数字'
  } else if (form.newPassword === form.oldPassword) {
    errors.newPassword = '新密码不能与当前密码相同'
  }
  if (!form.confirmPassword) {
    errors.confirmPassword = '请再次输入新密码'
  } else if (form.confirmPassword !== form.newPassword) {
    errors.confirmPassword = '两次输入的新密码不一致'
  }
  return !errors.oldPassword && !errors.newPassword && !errors.confirmPassword
}

async function submit(): Promise<void> {
  if (submitting.value || !validate()) return
  submitting.value = true
  errors.form = ''
  try {
    await auth.changePassword(form.oldPassword, form.newPassword)
    toast.success('密码已修改')
    emit('changed')
    emit('update:open', false)
  } catch (error) {
    const err = toApiError(error)
    if (err.code === 'AUTH_PASSWORD_WEAK') {
      errors.newPassword = err.message || '新密码强度不足，请更换更复杂的密码'
    } else if (err.code === 'AUTH_OLD_PASSWORD_WRONG') {
      errors.oldPassword = err.message || '当前密码不正确'
    } else if (err.fieldErrors?.length) {
      for (const item of err.fieldErrors) {
        if (item.field === 'newPassword') errors.newPassword = item.message
        if (item.field === 'oldPassword') errors.oldPassword = item.message
      }
    } else {
      errors.form = err.message || '修改密码失败，请稍后重试'
    }
  } finally {
    submitting.value = false
  }
}
</script>

<template>
  <Modal
    :open="open"
    :title="title"
    :description="description"
    size="sm"
    :closable="!forced"
    :close-on-overlay="!forced"
    @close="close"
  >
    <form class="cp" novalidate @submit.prevent="submit">
      <p v-if="forced" class="cp__banner" role="note">
        <svg viewBox="0 0 16 16" width="14" height="14" aria-hidden="true">
          <path
            d="M8 1.5a3.5 3.5 0 0 0-3.5 3.5v1.5H4a1 1 0 0 0-1 1V13a1 1 0 0 0 1 1h8a1 1 0 0 0 1-1V7.5a1 1 0 0 0-1-1h-.5V5A3.5 3.5 0 0 0 8 1.5Zm2 5H6V5a2 2 0 1 1 4 0v1.5Z"
            fill="currentColor"
          />
        </svg>
        初始密码为管理员下发的一次性密码，修改后不再提示。
      </p>

      <AppInput
        v-model="form.oldPassword"
        label="当前密码"
        type="password"
        autocomplete="current-password"
        required
        :error="errors.oldPassword"
        :readonly="submitting"
      />
      <AppInput
        v-model="form.newPassword"
        label="新密码"
        type="password"
        autocomplete="new-password"
        required
        hint="至少 8 位，建议包含字母与数字"
        :error="errors.newPassword"
        :readonly="submitting"
      />
      <AppInput
        v-model="form.confirmPassword"
        label="确认新密码"
        type="password"
        autocomplete="new-password"
        required
        :error="errors.confirmPassword"
        :readonly="submitting"
      />

      <p v-if="errors.form" class="cp__error" role="alert">{{ errors.form }}</p>
    </form>

    <template #footer>
      <AppButton v-if="!forced" variant="ghost" :disabled="submitting" @click="close">取消</AppButton>
      <AppButton variant="primary" :loading="submitting" loading-text="保存中…" @click="submit">
        保存新密码
      </AppButton>
    </template>
  </Modal>
</template>

<style scoped>
.cp {
  display: flex;
  flex-direction: column;
  gap: var(--sp-4);
}
.cp__banner {
  display: flex;
  align-items: flex-start;
  gap: var(--sp-2);
  margin: 0;
  padding: var(--sp-3);
  border: 1px solid var(--c-primary-border);
  border-radius: var(--r-md);
  background: var(--c-primary-soft);
  color: var(--c-primary);
  font-size: var(--fs-body-sm);
  line-height: var(--lh-body-sm);
}
.cp__banner svg {
  flex: none;
  margin-top: 2px;
}
.cp__error {
  margin: 0;
  color: var(--c-danger-text);
  font-size: var(--fs-body-sm);
  line-height: var(--lh-body-sm);
}
</style>
