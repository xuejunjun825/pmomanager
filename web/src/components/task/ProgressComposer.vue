<script setup lang="ts">
/**
 * 进度输入（DESIGN §3.4 / §4）：常驻输入区 + chip + 工具栏。
 *
 * 三条硬约束：
 * 1. 输入区在任何任务状态下都渲染（已完成/已取消同样可补进度），200 条上限只禁用不隐藏；
 * 2. 提交成功后把服务端返回行直接交给上层前插，输入与 chip 立即清空（1 秒内可见）；
 * 3. 只有 @ 选择器选中的成员才随请求提交，正文里手打「@姓名」只是普通文本（AC-9）。
 */
import { computed, nextTick, ref } from 'vue'
import AppButton from '@/components/ui/AppButton.vue'
import MentionChips from '@/components/task/MentionChips.vue'
import MentionPicker from '@/components/task/MentionPicker.vue'
import { taskApi } from '@/api/tasks'
import { usersApi } from '@/api/users'
import { toApiError } from '@/api/http'
import { useAuthStore } from '@/stores/auth'
import { useToast } from '@/composables/useToast'
import type { ProgressRow, UserOption } from '@/types/api'

const props = withDefaults(
  defineProps<{
    taskId: number
    /** 服务端 ProgressPage.total：200 条上限由它驱动，前端不自行计数 */
    total: number
    /** 可 @ 的成员名单；不传则由组件在首次打开选择器时拉取启用账号 */
    users?: UserOption[]
    selfId?: number
  }>(),
  { total: 0 },
)

const emit = defineEmits<{ created: [row: ProgressRow] }>()

const MAX_LENGTH = 500
const MAX_PROGRESS = 200
const MAX_MENTIONS = 10

const auth = useAuthStore()
const toast = useToast()

const content = ref('')
const mentions = ref<number[]>([])
const pickerOpen = ref(false)
const submitting = ref(false)
const forbidden = ref(false)
const error = ref('')
const textareaRef = ref<HTMLTextAreaElement | null>(null)
const roster = ref<UserOption[]>([])
const rosterLoaded = ref(false)
const rosterLoading = ref(false)

const limitReached = computed(() => props.total >= MAX_PROGRESS)
const length = computed(() => [...content.value].length)
const trimmed = computed(() => content.value.trim())
const full = computed(() => length.value > MAX_LENGTH)
const canSubmit = computed(
  () =>
    !limitReached.value &&
    !submitting.value &&
    !forbidden.value &&
    trimmed.value.length > 0 &&
    !full.value,
)

const selfId = computed(() => props.selfId ?? auth.user?.id)
const memberOptions = computed<UserOption[]>(() => {
  if (Array.isArray(props.users)) return props.users
  return roster.value
})
const selectedUsers = computed<UserOption[]>(() =>
  mentions.value
    .map((id) => memberOptions.value.find((user) => user.id === id))
    .filter((user): user is UserOption => Boolean(user)),
)

async function ensureRoster(): Promise<void> {
  if (Array.isArray(props.users) || rosterLoaded.value || rosterLoading.value) return
  rosterLoading.value = true
  try {
    const list = await usersApi.options()
    roster.value = Array.isArray(list) ? list : []
    rosterLoaded.value = true
  } catch (caught) {
    const apiError = toApiError(caught)
    toast.error(apiError.message || '成员名单加载失败，请重试')
  } finally {
    rosterLoading.value = false
  }
}

function openPicker(): void {
  if (limitReached.value || submitting.value) return
  pickerOpen.value = true
  void ensureRoster()
}

function closePicker(): void {
  pickerOpen.value = false
  void nextTick(() => textareaRef.value?.focus())
}

function togglePicker(): void {
  if (pickerOpen.value) closePicker()
  else openPicker()
}

/** 选中成员时在光标处补 @姓名；同一姓名已存在则不重复插入 */
function insertMentionText(user: UserOption): void {
  const token = `@${user.name}`
  if (content.value.includes(token)) return
  const element = textareaRef.value
  const start = element?.selectionStart ?? content.value.length
  const end = element?.selectionEnd ?? content.value.length
  const snippet = `${token} `
  content.value = `${content.value.slice(0, start)}${snippet}${content.value.slice(end)}`
  void nextTick(() => {
    element?.focus()
    const caret = start + snippet.length
    element?.setSelectionRange(caret, caret)
  })
}

function removeMention(id: number): void {
  mentions.value = mentions.value.filter((item) => item !== id)
}

function onInput(event: Event): void {
  const element = event.target as HTMLTextAreaElement
  const next = element.value
  const previous = content.value
  const inserted = (event as InputEvent).data ?? ''
  content.value = next
  if (error.value) error.value = ''
  if (inserted.includes('@') || (next.length === previous.length + 1 && next.endsWith('@'))) {
    openPicker()
  }
}

async function submit(): Promise<void> {
  if (submitting.value) return
  if (limitReached.value) {
    error.value = '该任务进度已达 200 条上限'
    return
  }
  if (!trimmed.value) {
    error.value = '请先写一句进展'
    return
  }
  if (full.value) {
    error.value = '进度内容最多 500 字'
    return
  }
  submitting.value = true
  error.value = ''
  const mentionCount = mentions.value.length
  try {
    const row = await taskApi.addProgress(props.taskId, {
      content: trimmed.value,
      mentionedUserIds: [...mentions.value],
    })
    pickerOpen.value = false
    content.value = ''
    mentions.value = []
    toast.success(
      mentionCount ? `进度已记录，已通知 ${mentionCount} 位成员` : '进度已记录',
    )
    emit('created', row)
    void nextTick(() => textareaRef.value?.focus())
  } catch (caught) {
    const apiError = toApiError(caught)
    if (apiError.status === 403) {
      forbidden.value = true
      error.value = ''
    } else if (apiError.status === 409) {
      error.value = '该任务进度已达 200 条上限'
    } else if (apiError.status === 422) {
      error.value =
        apiError.fieldError('content') ||
        apiError.fieldError('mentionedUserIds') ||
        '提交的内容不合法，请检查后重试'
    } else {
      error.value = apiError.message || '进度提交失败，请重试'
    }
  } finally {
    submitting.value = false
  }
}
</script>

<template>
  <section class="composer" aria-label="补充进度">
    <p v-if="forbidden" class="composer__forbidden" role="alert">你没有补充进度的权限</p>

    <template v-else>
      <MentionChips :users="selectedUsers" :disabled="limitReached || submitting" @remove="removeMention" />

      <div class="composer__field" :class="{ 'is-limit': limitReached, 'is-busy': submitting }">
        <textarea
          ref="textareaRef"
          class="composer__textarea"
          :value="content"
          rows="3"
          :placeholder="
            limitReached ? '该任务进度已达 200 条上限' : '记录进展，输入 @ 可提醒同事…'
          "
          :disabled="limitReached"
          :readonly="submitting"
          aria-label="记录进展"
          :aria-invalid="full || undefined"
          @input="onInput"
        />

        <div class="composer__toolbar">
          <button
            type="button"
            class="composer__mention"
            :disabled="limitReached || submitting"
            :aria-expanded="pickerOpen"
            aria-haspopup="listbox"
            @click="togglePicker"
          >
            @ 提及成员
          </button>
          <span class="composer__spacer" />
          <span class="composer__count num" :class="{ 'is-warn': length >= MAX_LENGTH }">
            {{ length }}/{{ MAX_LENGTH }}
          </span>
          <AppButton
            variant="primary"
            :disabled="!canSubmit"
            :loading="submitting"
            loading-text="提交中…"
            @click="submit"
          >
            提交
          </AppButton>
        </div>

        <MentionPicker
          v-if="pickerOpen"
          :model-value="mentions"
          :users="memberOptions"
          :self-id="selfId"
          :max="MAX_MENTIONS"
          @update:model-value="mentions = $event"
          @pick="insertMentionText"
          @close="closePicker"
        />
      </div>

      <p v-if="limitReached" class="composer__hint composer__hint--limit">
        该任务进度已达 200 条上限
      </p>
      <p v-else class="composer__hint">补充进度不会改变任务状态与看板数字</p>

      <p v-if="error" class="composer__error" role="alert">
        <span>{{ error }}</span>
        <button type="button" class="composer__retry" @click="submit">重试</button>
      </p>
    </template>
  </section>
</template>

<style scoped>
.composer {
  display: flex;
  flex-direction: column;
  margin-bottom: var(--sp-4);
}
.composer__forbidden {
  margin: 0;
  padding: var(--sp-3);
  border: 1px solid var(--c-line);
  border-radius: var(--r-lg);
  background: var(--c-line-soft);
  color: var(--c-ink-500);
  font-size: var(--fs-body-sm);
}
.composer__field {
  position: relative;
  padding: var(--sp-3) var(--sp-3) var(--sp-2);
  border: 1px solid var(--c-line);
  border-radius: var(--r-lg);
  background: var(--c-surface);
  transition: border-color var(--t-hover) var(--ease), box-shadow var(--t-hover) var(--ease);
}
.composer__field:focus-within {
  border-color: var(--c-primary);
  box-shadow: var(--focus-ring);
}
.composer__field.is-limit {
  background: var(--c-line-soft);
}
.composer__textarea {
  display: block;
  width: 100%;
  min-height: 88px;
  max-height: 160px;
  padding: 0;
  border: 0;
  outline: none;
  resize: vertical;
  background: transparent;
  color: var(--c-ink-900);
  font-family: inherit;
  font-size: var(--fs-body);
  line-height: 22px;
  overflow-y: auto;
}
.composer__textarea::placeholder {
  color: var(--c-ink-400);
}
.composer__textarea:disabled {
  cursor: not-allowed;
  color: var(--c-ink-500);
}
.composer__toolbar {
  display: flex;
  align-items: center;
  gap: var(--sp-2);
  height: 32px;
  margin-top: var(--sp-2);
}
.composer__spacer {
  flex: 1 1 auto;
}
.composer__mention {
  display: inline-flex;
  align-items: center;
  height: 28px;
  padding: 0 var(--sp-2);
  border: 0;
  border-radius: var(--r-sm);
  background: none;
  color: var(--c-primary);
  font-family: inherit;
  font-size: var(--fs-body-sm);
  font-weight: 500;
  cursor: pointer;
  transition: background var(--t-hover) var(--ease);
}
.composer__mention:hover:not(:disabled) {
  background: var(--c-primary-soft);
}
.composer__mention:disabled {
  color: var(--c-ink-400);
  cursor: not-allowed;
}
.composer__mention:focus-visible {
  outline: 2px solid var(--c-primary);
  outline-offset: 1px;
}
.composer__count {
  min-width: 48px;
  color: var(--c-ink-500);
  font-size: var(--fs-caption);
  line-height: 18px;
  text-align: right;
}
.composer__count.is-warn {
  color: var(--c-warn);
  font-weight: 600;
}
.composer__hint {
  margin: var(--sp-2) 0 0;
  color: var(--c-ink-500);
  font-size: var(--fs-caption);
  line-height: 18px;
}
.composer__hint--limit {
  color: var(--c-warn);
}
.composer__error {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--sp-2);
  margin: var(--sp-2) 0 0;
  padding: var(--sp-2) var(--sp-3);
  border: 1px solid var(--c-danger-border);
  border-radius: var(--r-sm);
  background: var(--c-danger-soft);
  color: var(--c-danger-text);
  font-size: var(--fs-caption);
}
.composer__retry {
  flex: none;
  padding: 0;
  border: 0;
  background: none;
  color: var(--c-danger-text);
  font-family: inherit;
  font-size: var(--fs-caption);
  font-weight: 600;
  text-decoration: underline;
  cursor: pointer;
}
</style>
