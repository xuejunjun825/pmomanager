<script setup lang="ts">
/**
 * 系统设置（DESIGN §4.11 / AC-20 / AC-21；r4 增项目配置卡）。
 *
 * 五块卡片：收件人 / 报表开关 / 来源配置 / 项目配置 / 钉钉配置状态。
 * - 收件人为空是**允许保存**的状态，但必须醒目提示「日报/周报将不会发送」；
 * - 来源配置与项目配置即时生效（行内操作直接落库），**不参与页面级「未保存变更」拦截**；
 * - 项目配置是管理员数据：非 ADMIN **整卡不渲染**（不是 disabled 占位），也不发请求；
 * - 钉钉配置只读展示缺失项名称，密钥永不回显，未配置不红色告警；
 * - 保存后重新 GET，以服务端返回为准，不在前端猜 PUT 的响应体。
 */
import { computed, onBeforeUnmount, onMounted, reactive, ref, watch } from 'vue'
import { onBeforeRouteLeave, useRouter } from 'vue-router'
import AppBadge from '@/components/ui/AppBadge.vue'
import AppButton from '@/components/ui/AppButton.vue'
import AppInput from '@/components/ui/AppInput.vue'
import ConfirmDialog from '@/components/ui/ConfirmDialog.vue'
import ErrorState from '@/components/ui/ErrorState.vue'
import PageHeader from '@/components/ui/PageHeader.vue'
import ProjectConfigTable from '@/components/settings/ProjectConfigTable.vue'
import SourceTypeTable from '@/components/settings/SourceTypeTable.vue'
import { projectsApi } from '@/api/projects'
import { settingsApi } from '@/api/settings'
import { sourceTypesApi } from '@/api/sourceTypes'
import { usersApi } from '@/api/users'
import { toApiError, type ApiError } from '@/api/http'
import { useToast } from '@/composables/useToast'
import { useAuthStore } from '@/stores/auth'
import { formatDateTime } from '@/utils/format'
import { ROLE_LABELS, type Role } from '@/types/enums'
import type { ProjectRow, SourcePaletteEntry, SourceTypeRow, UserOption } from '@/types/api'

interface Picked {
  id: number
  name: string
  role?: Role
}

/** 钉钉配置清单（DESIGN §4.11）：页面只判断「有没有」，不读取任何值 */
const DING_KEYS: { key: string; label: string }[] = [
  { key: 'corpid', label: 'CorpId' },
  { key: 'agentid', label: 'AgentId' },
  { key: 'appkey', label: 'AppKey' },
  { key: 'appsecret', label: 'AppSecret' },
  { key: 'callback', label: '回调域名' },
  { key: 'worknotice', label: '工作通知权限' },
]

const MAX_RECIPIENTS = 20
const router = useRouter()
const toast = useToast()

const form = reactive({
  dailyEnabled: true,
  dailyTime: '18:00',
  weeklyEnabled: true,
  weeklyTime: '09:00',
})
const picked = ref<Picked[]>([])
const dingtalk = reactive({ configured: true, missingKeys: [] as string[] })
const updatedAt = ref<string | null>(null)

const loading = ref(true)
const error = ref<ApiError | null>(null)
const saving = ref(false)
const snapshot = ref('')

const roster = ref<UserOption[]>([])
const rosterLoading = ref(false)
const rosterError = ref('')
const peopleKeyword = ref('')

/** 来源配置是独立数据源：与报表设置分开加载，加载失败不拖垮整页 */
const sourceTypes = ref<SourceTypeRow[]>([])
const palette = ref<SourcePaletteEntry[]>([])
const sourceLoading = ref(true)
const sourceError = ref('')

/** 项目配置同理：管理员数据，整卡由 isAdmin 控制是否渲染与是否请求 */
const auth = useAuthStore()
const isAdmin = computed(() => auth.role === 'ADMIN')
const projects = ref<ProjectRow[]>([])
const projectsLoading = ref(false)
const projectsError = ref('')

const leaveDialog = ref(false)
let allowLeave = false
let leaveTarget = ''

const payload = computed(() => ({
  dailyEnabled: form.dailyEnabled,
  dailyTime: form.dailyTime,
  weeklyEnabled: form.weeklyEnabled,
  weeklyTime: form.weeklyTime,
  recipientIds: picked.value.map((p) => p.id).sort((a, b) => a - b),
}))
const signature = computed(() => JSON.stringify(payload.value))
const dirty = computed(() => !loading.value && !error.value && signature.value !== snapshot.value)

const timeInvalid = computed(() => {
  const ok = (value: string) => /^([01]\d|2[0-3]):[0-5]\d$/.test(value)
  return (form.dailyEnabled && !ok(form.dailyTime)) || (form.weeklyEnabled && !ok(form.weeklyTime))
})
const timeError = computed(() => (timeInvalid.value ? '请填写 24 小时制时间，例如 18:00' : ''))

const recipientsEmpty = computed(() => picked.value.length === 0)
const candidates = computed(() => {
  const chosen = new Set(picked.value.map((p) => p.id))
  const keyword = peopleKeyword.value.trim()
  return roster.value
    .filter((user) => user.status === 'ACTIVE' && !chosen.has(user.id))
    .filter((user) => (keyword ? user.name.includes(keyword) : true))
    .slice(0, 8)
})
const canAddMore = computed(() => picked.value.length < MAX_RECIPIENTS)
const removedHint = computed(() =>
  picked.value.length > 5 ? '已超过建议人数（建议 1–5 人），保存后仍会发送。' : '建议 1–5 人，最多 20 人。',
)

/** 缺失项名称归一化后与清单做包含匹配：DINGTALK_CORP_ID / corpId 都能对上 */
function normalize(value: string): string {
  return value.toLowerCase().replace(/[^a-z0-9]/g, '')
}
const missingSet = computed(() => dingtalk.missingKeys.map(normalize))
function isMissing(key: string): boolean {
  if (dingtalk.configured && dingtalk.missingKeys.length === 0) return false
  return missingSet.value.some((item) => item.includes(key) || key.includes(item))
}
const extraMissing = computed(() =>
  dingtalk.missingKeys.filter((raw) => !DING_KEYS.some((item) => normalize(raw).includes(item.key))),
)

function applyView(report: {
  dailyEnabled: boolean
  dailyTime: string
  weeklyEnabled: boolean
  weeklyTime: string
  recipients: { id: number; name: string; role?: Role }[]
}): void {
  form.dailyEnabled = report.dailyEnabled
  form.dailyTime = report.dailyTime
  form.weeklyEnabled = report.weeklyEnabled
  form.weeklyTime = report.weeklyTime
  picked.value = report.recipients.map((r) => ({ id: r.id, name: r.name, role: r.role }))
}

async function load(): Promise<void> {
  loading.value = true
  error.value = null
  try {
    const view = await settingsApi.get()
    applyView(view.report)
    dingtalk.configured = view.dingtalk.configured
    dingtalk.missingKeys = view.dingtalk.missingKeys ?? []
    updatedAt.value = view.updatedAt ?? null
    snapshot.value = JSON.stringify(payload.value)
  } catch (e) {
    error.value = toApiError(e)
  } finally {
    loading.value = false
  }
}

async function loadRoster(): Promise<void> {
  rosterLoading.value = true
  rosterError.value = ''
  try {
    roster.value = await usersApi.options()
  } catch (e) {
    rosterError.value = toApiError(e).message || '人员列表加载失败'
  } finally {
    rosterLoading.value = false
  }
}

function addRecipient(user: UserOption): void {
  if (!canAddMore.value) {
    toast.warning(`最多选择 ${MAX_RECIPIENTS} 位收件人`)
    return
  }
  if (picked.value.some((p) => p.id === user.id)) return
  picked.value = [...picked.value, { id: user.id, name: user.name, role: user.role }]
}

function removeRecipient(id: number): void {
  picked.value = picked.value.filter((p) => p.id !== id)
}

async function save(): Promise<void> {
  if (timeInvalid.value) {
    toast.warning(timeError.value)
    return
  }
  saving.value = true
  try {
    await settingsApi.updateReport(payload.value)
    const view = await settingsApi.get()
    applyView(view.report)
    dingtalk.configured = view.dingtalk.configured
    dingtalk.missingKeys = view.dingtalk.missingKeys ?? []
    updatedAt.value = view.updatedAt ?? null
    snapshot.value = JSON.stringify(payload.value)
    toast.success('设置已保存，下一次报表任务将按新配置执行')
  } catch (e) {
    toast.error(toApiError(e).message || '保存失败，请重试')
  } finally {
    saving.value = false
  }
}

/** 放弃更改 = 回到服务端最后一次保存的状态（不猜快照） */
function resetForm(): void {
  void load()
}

function confirmLeave(): void {
  allowLeave = true
  leaveDialog.value = false
  const target = leaveTarget
  leaveTarget = ''
  if (target) void router.push(target)
}

onBeforeRouteLeave((to) => {
  if (allowLeave || !dirty.value || saving.value) return true
  leaveTarget = to.fullPath
  leaveDialog.value = true
  return false
})

function onBeforeUnload(event: BeforeUnloadEvent): void {
  if (!dirty.value || saving.value) return
  event.preventDefault()
  event.returnValue = ''
}

async function loadSources(): Promise<void> {
  sourceLoading.value = true
  sourceError.value = ''
  try {
    const [rows, entries] = await Promise.all([sourceTypesApi.list(), sourceTypesApi.palette()])
    sourceTypes.value = rows
    palette.value = entries
  } catch (e) {
    const apiError = toApiError(e)
    sourceError.value = apiError.message || '来源配置加载失败，请重试。'
  } finally {
    sourceLoading.value = false
  }
}

/** 项目配置：失败只影响本卡（错误提示 + 重试），不拖垮整页；非管理员不请求 */
async function loadProjects(): Promise<void> {
  if (!isAdmin.value) {
    projectsLoading.value = false
    return
  }
  projectsLoading.value = true
  projectsError.value = ''
  try {
    projects.value = await projectsApi.list()
  } catch (e) {
    const apiError = toApiError(e)
    projectsError.value = apiError.message || '项目配置加载失败，请重试。'
  } finally {
    projectsLoading.value = false
  }
}

watch(dirty, (value) => {
  if (value) window.addEventListener('beforeunload', onBeforeUnload)
  else window.removeEventListener('beforeunload', onBeforeUnload)
})

onMounted(() => {
  void load()
  void loadRoster()
  void loadSources()
  void loadProjects()
})

onBeforeUnmount(() => {
  window.removeEventListener('beforeunload', onBeforeUnload)
})
</script>

<template>
  <div class="settings">
    <PageHeader title="系统设置" description="报表生效范围、来源类型与钉钉部署状态；配置改动即时留痕。">
      <template #meta>
        <p v-if="updatedAt" class="settings__stamp">上次更新 {{ formatDateTime(updatedAt) }}</p>
      </template>
    </PageHeader>

    <div v-if="loading" class="settings__loading" aria-busy="true" aria-live="polite">
      <span class="visually-hidden">正在加载设置</span>
      <div v-for="n in 5" :key="`sk-${n}`" class="card card--skeleton">
        <span class="sk-block sk-title" />
        <span class="sk-block sk-line" />
        <span class="sk-block sk-line sk-line--short" />
      </div>
    </div>

    <ErrorState
      v-else-if="error"
      title="设置加载失败"
      :description="error.message || '请检查网络后重试。'"
      :trace-id="error.traceId"
      @retry="load()"
    />

    <template v-else>
      <!-- ① 收件人 -->
      <section class="card" aria-labelledby="recipients-title">
        <header class="card__head">
          <h2 id="recipients-title" class="card__title">督办日报收件人</h2>
          <p class="card__desc">日报/周报只发给名单内的领导，可包含非管理层账号。</p>
        </header>

        <p v-if="recipientsEmpty" class="banner" role="status">
          未配置收件人，日报/周报将不会发送。请至少添加 1 位收件人。
        </p>

        <div class="picked" aria-label="已选收件人">
          <span v-if="recipientsEmpty" class="picked__empty">尚未选择收件人</span>
          <span v-for="person in picked" :key="person.id" class="picked__chip">
            {{ person.name }}
            <span v-if="person.role" class="picked__role">{{ ROLE_LABELS[person.role] }}</span>
            <button
              type="button"
              class="picked__remove"
              :aria-label="`移除收件人 ${person.name}`"
              @click="removeRecipient(person.id)"
            >
              ×
            </button>
          </span>
        </div>

        <div class="picker">
          <div class="picker__search">
            <AppInput
              v-model="peopleKeyword"
              type="search"
              placeholder="搜索姓名或账号以添加"
              aria-label="搜索收件人"
            />
          </div>
          <p class="picker__hint" :class="{ 'picker__hint--warn': picked.length > 5 }">{{ removedHint }}</p>

          <div v-if="rosterError" class="picker__error">
            {{ rosterError }}
            <AppButton variant="ghost" size="compact" @click="loadRoster">重试</AppButton>
          </div>
          <ul v-else-if="candidates.length" class="candidates">
            <li v-for="user in candidates" :key="user.id">
              <button type="button" class="candidate" :disabled="!canAddMore" @click="addRecipient(user)">
                <span class="candidate__name">{{ user.name }}</span>
                <span class="candidate__meta">{{ ROLE_LABELS[user.role] }}</span>
                <span class="candidate__add" aria-hidden="true">+</span>
              </button>
            </li>
          </ul>
          <p v-else-if="rosterLoading" class="picker__hint">正在加载人员…</p>
          <p v-else class="picker__hint">
            {{ peopleKeyword ? '没有匹配的账号' : '可添加的账号都已选入名单' }}
          </p>
        </div>

        <p class="card__foot">保存时会记录操作人、时间与变更项，可在日报任务记录中查看。</p>
      </section>

      <!-- ② 报表开关 -->
      <section class="card" aria-labelledby="report-title">
        <header class="card__head">
          <h2 id="report-title" class="card__title">报表开关</h2>
          <p class="card__desc">关闭后只是不发送，站内任务与看板不受影响。</p>
        </header>

        <div class="row">
          <div class="row__text">
            <span class="row__name">每日督办日报</span>
            <span class="row__hint">汇总当日 KPI、逾期与焦点任务</span>
          </div>
          <div class="row__control">
            <label class="time">
              <span class="visually-hidden">日报发送时间</span>
              <input
                v-model="form.dailyTime"
                type="time"
                class="time__input"
                step="60"
                :disabled="!form.dailyEnabled"
                :aria-invalid="timeError ? 'true' : undefined"
              />
            </label>
            <button
              type="button"
              class="switch"
              role="switch"
              :aria-checked="form.dailyEnabled"
              aria-label="每日督办日报开关"
              @click="form.dailyEnabled = !form.dailyEnabled"
            >
              <span class="switch__track"><span class="switch__thumb" /></span>
              <span class="switch__text">{{ form.dailyEnabled ? '已开启' : '已关闭' }}</span>
            </button>
          </div>
        </div>
        <p v-if="!form.dailyEnabled" class="row__off">日报已关闭：到点不会发送，站内提醒与看板照常。</p>

        <div class="row row--divide">
          <div class="row__text">
            <span class="row__name">每周督办周报</span>
            <span class="row__hint">每周一汇总上一周进展</span>
          </div>
          <div class="row__control">
            <label class="time">
              <span class="visually-hidden">周报发送时间</span>
              <input
                v-model="form.weeklyTime"
                type="time"
                class="time__input"
                step="60"
                :disabled="!form.weeklyEnabled"
                :aria-invalid="timeError ? 'true' : undefined"
              />
            </label>
            <button
              type="button"
              class="switch"
              role="switch"
              :aria-checked="form.weeklyEnabled"
              aria-label="每周督办周报开关"
              @click="form.weeklyEnabled = !form.weeklyEnabled"
            >
              <span class="switch__track"><span class="switch__thumb" /></span>
              <span class="switch__text">{{ form.weeklyEnabled ? '已开启' : '已关闭' }}</span>
            </button>
          </div>
        </div>
        <p v-if="!form.weeklyEnabled" class="row__off">周报已关闭：每周一不会发送。</p>
        <p v-if="timeError" class="row__error" role="alert">{{ timeError }}</p>
      </section>

      <!-- ③ 来源配置（即时生效，不参与本页「未保存」拦截） -->
      <section id="source-types" class="card" aria-labelledby="source-types-title">
        <header class="card__head card__head--inline">
          <div>
            <h2 id="source-types-title" class="card__title">来源配置</h2>
            <p class="card__desc">
              任务来源的可选类型、形态与标签颜色；行内改动即时生效，无需保存。
            </p>
          </div>
        </header>
        <SourceTypeTable
          :items="sourceTypes"
          :palette="palette"
          :loading="sourceLoading"
          :error="sourceError"
          @refresh="loadSources"
        />
      </section>

      <!-- ④ 项目配置（即时生效；仅管理员渲染整卡，失败只影响本卡） -->
      <section v-if="isAdmin" id="projects" class="card" aria-labelledby="projects-title">
        <header class="card__head card__head--inline">
          <div>
            <h2 id="projects-title" class="card__title">项目配置</h2>
            <p class="card__desc">
              项目是任务来源的可选绑定对象；行内改动即时生效，无需保存。
            </p>
          </div>
        </header>
        <ProjectConfigTable
          :items="projects"
          :loading="projectsLoading"
          :error="projectsError"
          @refresh="loadProjects"
        />
      </section>

      <!-- ⑤ 钉钉配置状态（只读） -->
      <section class="card" aria-labelledby="dingtalk-title">
        <header class="card__head card__head--inline">
          <div>
            <h2 id="dingtalk-title" class="card__title">钉钉配置状态</h2>
            <p class="card__desc">由部署配置（环境变量）提供，页面不可编辑；密钥永不回显。</p>
          </div>
          <AppBadge :tone="dingtalk.configured ? 'success' : 'neutral'" dot>
            {{ dingtalk.configured ? '已配置' : '未配置' }}
          </AppBadge>
        </header>

        <p v-if="!dingtalk.configured" class="ding-note">
          钉钉推送不会发出（含每日提醒与催办），站内通知照常；登录入口仅保留本地账号密码。
        </p>

        <ul class="checks">
          <li
            v-for="item in DING_KEYS"
            :key="item.key"
            class="checks__item"
            :class="{ 'checks__item--missing': isMissing(item.key) }"
          >
            <span class="checks__mark" aria-hidden="true">{{ isMissing(item.key) ? '—' : '✓' }}</span>
            <span class="checks__label">{{ item.label }}</span>
            <span class="checks__state">{{ isMissing(item.key) ? '缺失' : '已提供' }}</span>
          </li>
          <li v-for="item in extraMissing" :key="`extra-${item}`" class="checks__item checks__item--missing">
            <span class="checks__mark" aria-hidden="true">—</span>
            <span class="checks__label mono">{{ item }}</span>
            <span class="checks__state">缺失</span>
          </li>
        </ul>
      </section>

      <!-- 底部保存条：有变更才出现 -->
      <Transition name="savebar">
        <div v-if="dirty" class="savebar" role="region" aria-label="未保存的更改">
          <p class="savebar__text">有未保存的更改</p>
          <div class="savebar__actions">
            <AppButton variant="ghost" :disabled="saving" @click="resetForm">放弃更改</AppButton>
            <AppButton variant="primary" :loading="saving" :disabled="timeInvalid" @click="save">保存设置</AppButton>
          </div>
        </div>
      </Transition>
    </template>

    <ConfirmDialog
      :open="leaveDialog"
      title="离开并放弃更改？"
      description="当前设置尚未保存，离开后本次修改将丢失。"
      confirm-label="放弃并离开"
      tone="danger"
      @confirm="confirmLeave"
      @close="leaveDialog = false"
    />
  </div>
</template>

<style scoped>
.settings__stamp {
  margin: 6px 0 0;
  font-size: var(--fs-caption);
  color: var(--c-ink-500);
}
.settings__loading {
  display: flex;
  flex-direction: column;
  gap: var(--sp-4);
}
.card {
  padding: var(--sp-5);
  background: var(--c-surface);
  border: 1px solid var(--c-line);
  border-radius: var(--r-lg);
  box-shadow: var(--sh-1);
}
.card + .card {
  margin-top: var(--sp-4);
}
.card--skeleton {
  display: flex;
  flex-direction: column;
  gap: var(--sp-3);
}
.sk-title {
  width: 180px;
  height: 16px;
}
.sk-line {
  width: 70%;
  height: 12px;
}
.sk-line--short {
  width: 40%;
}
.card__head {
  margin-bottom: var(--sp-3);
}
.card__head--inline {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: var(--sp-4);
}
.card__title {
  margin: 0;
  font-size: var(--fs-h3);
  line-height: var(--lh-h3);
  font-weight: 600;
  color: var(--c-ink-900);
}
.card__desc {
  margin: 4px 0 0;
  font-size: var(--fs-body-sm);
  color: var(--c-ink-500);
}
.card__foot {
  margin: var(--sp-4) 0 0;
  padding-top: var(--sp-3);
  border-top: 1px solid var(--c-line-soft);
  font-size: var(--fs-caption);
  color: var(--c-ink-400);
}

.banner {
  margin: 0 0 var(--sp-3);
  padding: 10px var(--sp-3);
  border: 1px solid var(--c-warn-border);
  border-radius: var(--r-md);
  background: var(--c-warn-soft);
  color: var(--c-warn);
  font-size: var(--fs-body-sm);
  line-height: var(--lh-body-sm);
}

.picked {
  display: flex;
  flex-wrap: wrap;
  gap: var(--sp-2);
  min-height: 32px;
  align-items: center;
  margin-bottom: var(--sp-3);
}
.picked__empty {
  font-size: var(--fs-body-sm);
  color: var(--c-ink-400);
}
.picked__chip {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  height: 28px;
  padding: 0 4px 0 10px;
  border: 1px solid var(--c-primary-border);
  border-radius: var(--r-pill);
  background: var(--c-primary-soft);
  color: var(--c-primary);
  font-size: var(--fs-body-sm);
}
.picked__role {
  font-size: var(--fs-micro);
  color: var(--c-ink-500);
}
.picked__remove {
  width: 20px;
  height: 20px;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  border: 0;
  border-radius: 50%;
  background: transparent;
  color: var(--c-primary);
  font-size: 14px;
  line-height: 1;
  cursor: pointer;
  transition: background var(--t-hover) var(--ease);
}
.picked__remove:hover {
  background: var(--c-primary-border);
}

.picker__search {
  width: 320px;
  max-width: 100%;
}
.picker__hint {
  margin: var(--sp-2) 0 0;
  font-size: var(--fs-caption);
  color: var(--c-ink-400);
}
.picker__hint--warn {
  color: var(--c-warn);
}
.picker__error {
  margin-top: var(--sp-2);
  font-size: var(--fs-caption);
  color: var(--c-danger-text);
}
.candidates {
  list-style: none;
  margin: var(--sp-2) 0 0;
  padding: 0;
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(220px, 1fr));
  gap: var(--sp-2);
}
.candidate {
  display: flex;
  align-items: center;
  gap: var(--sp-2);
  width: 100%;
  padding: 8px var(--sp-3);
  border: 1px solid var(--c-line);
  border-radius: var(--r-md);
  background: var(--c-surface);
  font-family: inherit;
  text-align: left;
  cursor: pointer;
  transition:
    border-color var(--t-hover) var(--ease),
    background var(--t-hover) var(--ease);
}
.candidate:hover:not(:disabled) {
  border-color: var(--c-primary-border);
  background: var(--c-primary-soft);
}
.candidate:disabled {
  cursor: not-allowed;
  background: var(--c-line-soft);
  color: var(--c-ink-400);
}
.candidate__name {
  font-size: var(--fs-body-sm);
  font-weight: 500;
  color: var(--c-ink-900);
}
.candidate__meta {
  flex: 1;
  min-width: 0;
  font-size: var(--fs-micro);
  color: var(--c-ink-500);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.candidate__add {
  flex: none;
  color: var(--c-primary);
  font-size: 14px;
}

.row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--sp-4);
  padding: var(--sp-2) 0;
}
.row--divide {
  border-top: 1px solid var(--c-line-soft);
  padding-top: var(--sp-3);
  margin-top: var(--sp-2);
}
.row__text {
  display: flex;
  flex-direction: column;
  gap: 2px;
  min-width: 0;
}
.row__name {
  font-size: var(--fs-body);
  font-weight: 500;
  color: var(--c-ink-900);
}
.row__hint {
  font-size: var(--fs-caption);
  color: var(--c-ink-500);
}
.row__control {
  display: flex;
  align-items: center;
  gap: var(--sp-3);
  flex: none;
}
.row__off,
.row__error {
  margin: var(--sp-1) 0 0;
  font-size: var(--fs-caption);
}
.row__off {
  color: var(--c-ink-500);
}
.row__error {
  color: var(--c-danger-text);
}

.time__input {
  height: 32px;
  padding: 0 var(--sp-2);
  border: 1px solid var(--c-line);
  border-radius: var(--r-md);
  background: var(--c-surface);
  color: var(--c-ink-900);
  font-family: var(--font-num);
  font-size: var(--fs-body-sm);
  font-variant-numeric: tabular-nums;
  transition: border-color var(--t-hover) var(--ease);
}
.time__input:hover:not(:disabled) {
  border-color: var(--c-ink-400);
}
.time__input:disabled {
  background: var(--c-line-soft);
  color: var(--c-ink-400);
  cursor: not-allowed;
}

.switch {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  padding: 2px;
  border: 0;
  background: none;
  font-family: inherit;
  font-size: var(--fs-caption);
  color: var(--c-ink-600);
  cursor: pointer;
}
.switch__track {
  position: relative;
  width: 32px;
  height: 18px;
  flex: none;
  border-radius: var(--r-pill);
  background: var(--c-line);
  transition: background var(--t-toggle) var(--ease);
}
.switch__thumb {
  position: absolute;
  top: 2px;
  left: 2px;
  width: 14px;
  height: 14px;
  border-radius: 50%;
  background: #fff;
  box-shadow: var(--sh-1);
  transition: transform var(--t-toggle) var(--ease);
}
.switch[aria-checked='true'] .switch__track {
  background: var(--c-primary);
}
.switch[aria-checked='true'] .switch__thumb {
  transform: translateX(14px);
}

.ding-note {
  margin: 0 0 var(--sp-3);
  padding: 10px var(--sp-3);
  border: 1px solid var(--c-line);
  border-radius: var(--r-md);
  background: var(--c-line-soft);
  color: var(--c-ink-600);
  font-size: var(--fs-body-sm);
}
.checks {
  list-style: none;
  margin: 0;
  padding: 0;
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(240px, 1fr));
  gap: var(--sp-1) var(--sp-4);
}
.checks__item {
  display: flex;
  align-items: center;
  gap: var(--sp-2);
  padding: 6px 0;
  font-size: var(--fs-body-sm);
  border-bottom: 1px dashed var(--c-line-soft);
}
.checks__mark {
  width: 16px;
  flex: none;
  color: var(--c-success);
  text-align: center;
}
.checks__label {
  flex: 1;
  min-width: 0;
  color: var(--c-ink-700);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.checks__state {
  flex: none;
  font-size: var(--fs-caption);
  color: var(--c-ink-400);
}
.checks__item--missing .checks__mark,
.checks__item--missing .checks__state {
  color: var(--c-warn);
}

.savebar {
  position: sticky;
  bottom: 0;
  z-index: 2;
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--sp-4);
  height: 56px;
  margin-top: var(--sp-4);
  padding: 0 var(--sp-4);
  background: var(--c-surface);
  border: 1px solid var(--c-line);
  border-radius: var(--r-lg);
  box-shadow: var(--sh-2);
}
.savebar__text {
  margin: 0;
  font-size: var(--fs-body-sm);
  color: var(--c-ink-700);
}
.savebar__actions {
  display: flex;
  align-items: center;
  gap: var(--sp-2);
}
.savebar-enter-active,
.savebar-leave-active {
  transition:
    opacity var(--t-panel) var(--ease),
    transform var(--t-panel) var(--ease);
}
.savebar-enter-from,
.savebar-leave-to {
  opacity: 0;
  transform: translateY(8px);
}

@media (max-width: 720px) {
  .row {
    flex-direction: column;
    align-items: flex-start;
  }
  .row__control {
    width: 100%;
    justify-content: space-between;
  }
  .savebar {
    height: auto;
    padding: var(--sp-2) var(--sp-4);
    flex-wrap: wrap;
  }
}
</style>
