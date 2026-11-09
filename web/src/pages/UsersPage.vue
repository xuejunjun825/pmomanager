<script setup lang="ts">
/**
 * 用户与角色（DESIGN §4.12 / AC-18）。
 *
 * 角色与状态是管理员手工维护的唯一入口；停用与重置密码都是一次性不可逆动作，
 * 因此都走二次确认，且重置出的临时密码只在结果弹窗里存在一次，关闭即从内存清掉。
 */
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import AppBadge from '@/components/ui/AppBadge.vue'
import AppButton from '@/components/ui/AppButton.vue'
import AppInput from '@/components/ui/AppInput.vue'
import AppSelect from '@/components/ui/AppSelect.vue'
import ConfirmDialog from '@/components/ui/ConfirmDialog.vue'
import DataTable, { type TableColumn } from '@/components/ui/DataTable.vue'
import ErrorState from '@/components/ui/ErrorState.vue'
import Modal from '@/components/ui/Modal.vue'
import PageHeader from '@/components/ui/PageHeader.vue'
import Pagination from '@/components/ui/Pagination.vue'
import { usersApi } from '@/api/users'
import { toApiError, type ApiError } from '@/api/http'
import { useAuthStore } from '@/stores/auth'
import { useToast } from '@/composables/useToast'
import { formatDateTime, initialOf } from '@/utils/format'
import { ROLES, ROLE_LABELS, USER_STATUS_LABELS, type Role, type UserStatus } from '@/types/enums'
import type { UserRow } from '@/types/api'

const auth = useAuthStore()
const toast = useToast()

const columns: TableColumn[] = [
  { key: 'name', label: '姓名', width: '200px' },
  { key: 'username', label: '登录账号', width: '140px' },
  { key: 'dingtalkBound', label: '钉钉绑定', width: '120px' },
  { key: 'role', label: '角色', width: '110px' },
  { key: 'status', label: '状态', width: '96px' },
  { key: 'lastLoginAt', label: '最近登录', width: '140px' },
  { key: 'actions', label: '操作', width: '96px', align: 'right' },
]

const roleOptions = [
  { label: '全部角色', value: '' },
  ...ROLES.map((role) => ({ label: ROLE_LABELS[role], value: role })),
]
const statusOptions = [
  { label: '全部状态', value: '' },
  { label: USER_STATUS_LABELS.ACTIVE, value: 'ACTIVE' },
  { label: USER_STATUS_LABELS.DISABLED, value: 'DISABLED' },
]

const ROLE_HINTS: Record<Role, string> = {
  ADMIN: '全部权限：任务、会议、用户与系统设置',
  OWNER: '查看本人任务、提交与催办，不能验收',
  EXEC: '全量任务/矩阵/看板，可改派改期并督办',
}

const items = ref<UserRow[]>([])
const total = ref(0)
const page = ref(1)
const size = ref(20)
const keyword = ref('')
const roleFilter = ref<'' | Role>('')
const statusFilter = ref<'' | UserStatus>('')
const loading = ref(false)
const error = ref<ApiError | null>(null)

const roleDialog = ref<{ user: UserRow; next: Role } | null>(null)
const savingRole = ref(false)
const statusTarget = ref<UserRow | null>(null)
const savingStatus = ref(false)
const resetTarget = ref<UserRow | null>(null)
const resetting = ref(false)
const temporary = ref('')
const temporaryOwner = ref('')
const busyId = ref<number | null>(null)

const selfId = computed(() => auth.user?.id ?? null)
let searchTimer: ReturnType<typeof setTimeout> | null = null

async function load(): Promise<void> {
  loading.value = true
  error.value = null
  try {
    const data = await usersApi.page({
      keyword: keyword.value.trim() || undefined,
      role: roleFilter.value,
      status: statusFilter.value,
      page: page.value,
      size: size.value,
    })
    items.value = data.items ?? []
    total.value = data.total ?? 0
  } catch (e) {
    error.value = toApiError(e)
  } finally {
    loading.value = false
  }
}

function reloadFirstPage(): void {
  page.value = 1
  void load()
}

function onKeywordInput(): void {
  if (searchTimer) clearTimeout(searchTimer)
  searchTimer = setTimeout(reloadFirstPage, 300)
}

// 关键词防抖：输入 300ms 后才查，避免每敲一个字打一次接口
watch(keyword, () => onKeywordInput())

function changePage(next: number): void {
  page.value = next
  void load()
}

function changeSize(next: number): void {
  size.value = next
  page.value = 1
  void load()
}

function patchRow(updated: UserRow): void {
  items.value = items.value.map((row) => (row.id === updated.id ? updated : row))
}

/** 最近登录来自 users.last_login_at；从未登录为 null，按「—」降级展示 */
function lastLoginOf(row: UserRow): string {
  return row.lastLoginAt ? formatDateTime(row.lastLoginAt) : '—'
}

function openRole(row: UserRow): void {
  roleDialog.value = { user: row, next: row.role }
}

async function saveRole(): Promise<void> {
  const dialog = roleDialog.value
  if (!dialog || dialog.next === dialog.user.role) return
  savingRole.value = true
  try {
    const updated = await usersApi.updateRole(dialog.user.id, dialog.next)
    patchRow(updated)
    toast.success(`${updated.name} 的角色已改为${ROLE_LABELS[updated.role]}`)
    roleDialog.value = null
  } catch (e) {
    toast.error(toApiError(e).message || '角色修改失败')
  } finally {
    savingRole.value = false
  }
}

function askToggleStatus(row: UserRow): void {
  if (row.id === selfId.value) {
    toast.warning('不能停用自己的账号')
    return
  }
  if (row.status === 'ACTIVE') {
    statusTarget.value = row
    return
  }
  void applyStatus(row, 'ACTIVE')
}

async function applyStatus(row: UserRow, status: UserStatus): Promise<void> {
  savingStatus.value = true
  busyId.value = row.id
  try {
    const updated = await usersApi.updateStatus(row.id, status)
    patchRow(updated)
    statusTarget.value = null
    toast.success(status === 'ACTIVE' ? `已启用 ${updated.name}` : `已停用 ${updated.name}`)
  } catch (e) {
    const err = toApiError(e)
    toast.error(err.code === 'SELF_DISABLE_FORBIDDEN' ? '不能停用自己的账号' : err.message || '状态修改失败')
  } finally {
    savingStatus.value = false
    busyId.value = null
  }
}

function askReset(row: UserRow): void {
  resetTarget.value = row
}

async function doReset(): Promise<void> {
  const row = resetTarget.value
  if (!row) return
  resetting.value = true
  try {
    const result = await usersApi.resetPassword(row.id)
    temporary.value = result.temporaryPassword
    temporaryOwner.value = row.name
    resetTarget.value = null
  } catch (e) {
    toast.error(toApiError(e).message || '重置密码失败')
  } finally {
    resetting.value = false
  }
}

async function copyTemporary(): Promise<void> {
  try {
    await navigator.clipboard.writeText(temporary.value)
    toast.success('临时密码已复制')
  } catch {
    toast.warning('复制失败，请手动选择文本复制')
  }
}

function closeTemporary(): void {
  temporary.value = ''
  temporaryOwner.value = ''
}

onMounted(() => {
  void load()
})

onBeforeUnmount(() => {
  if (searchTimer) clearTimeout(searchTimer)
})
</script>

<template>
  <div class="users">
    <PageHeader title="用户管理" description="角色由管理员手工分配；钉钉只提供身份，不提供角色。">
      <template #meta>
        <p class="users__hint">
          <span class="num">{{ total }}</span> 个账号 · 停用后无法登录（含钉钉），名下任务保留
        </p>
      </template>
    </PageHeader>

    <div class="filters">
      <div class="filters__search">
        <AppInput
          v-model="keyword"
          type="search"
          placeholder="搜索姓名或登录账号"
          aria-label="搜索用户"
          @enter="reloadFirstPage"
        />
      </div>
      <AppSelect
        :model-value="roleFilter"
        :options="roleOptions"
        aria-label="按角色筛选"
        @update:model-value="(v: string) => { roleFilter = v as '' | Role; reloadFirstPage() }"
      />
      <AppSelect
        :model-value="statusFilter"
        :options="statusOptions"
        aria-label="按状态筛选"
        @update:model-value="(v: string) => { statusFilter = v as '' | UserStatus; reloadFirstPage() }"
      />
    </div>

    <ErrorState
      v-if="error && !loading"
      title="用户列表加载失败"
      :description="error.message || '请检查网络后重试。'"
      :trace-id="error.traceId"
      @retry="load()"
    />

    <DataTable
      v-else
      :columns="columns"
      :rows="items"
      :loading="loading"
      empty-title="没有匹配的账号"
      empty-hint="换个关键词，或清空角色/状态筛选再看。"
    >
      <template #cell-name="{ row }">
        <span class="who">
          <span class="who__avatar" aria-hidden="true">{{ initialOf(row.name) }}</span>
          <span class="who__name">{{ row.name }}</span>
        </span>
      </template>

      <template #cell-username="{ row }">
        <span class="mono">{{ row.username }}</span>
      </template>

      <template #cell-dingtalkBound="{ row }">
        <span v-if="row.dingtalkBound" class="bound">
          <AppBadge tone="success" size="sm" dot>已绑定</AppBadge>
        </span>
        <AppBadge v-else tone="neutral" size="sm">未绑定</AppBadge>
      </template>

      <template #cell-role="{ row }">
        <button
          type="button"
          class="role"
          :aria-label="`修改 ${row.name} 的角色，当前${ROLE_LABELS[row.role]}`"
          @click="openRole(row)"
        >
          <AppBadge :tone="row.role === 'ADMIN' ? 'purple' : row.role === 'EXEC' ? 'info' : 'neutral'" size="sm">
            {{ ROLE_LABELS[row.role] }}
          </AppBadge>
          <span class="role__edit" aria-hidden="true">改</span>
        </button>
      </template>

      <template #cell-status="{ row }">
        <button
          type="button"
          class="switch"
          role="switch"
          :aria-checked="row.status === 'ACTIVE'"
          :aria-label="`${row.name} 账号${row.status === 'ACTIVE' ? '已启用，点击停用' : '已停用，点击启用'}`"
          :disabled="row.id === selfId || busyId === row.id"
          :title="row.id === selfId ? '不能停用自己的账号' : undefined"
          @click="askToggleStatus(row)"
        >
          <span class="switch__track"><span class="switch__thumb" /></span>
          <span class="switch__text">{{ row.status === 'ACTIVE' ? '启用' : '停用' }}</span>
        </button>
      </template>

      <template #cell-lastLoginAt="{ row }">
        <span class="num text-muted">{{ lastLoginOf(row) }}</span>
      </template>

      <template #cell-actions="{ row }">
        <AppButton variant="ghost" size="compact" @click="askReset(row)">重置密码</AppButton>
      </template>
    </DataTable>

    <div v-if="!error && total > 0" class="users__pager">
      <Pagination
        :page="page"
        :size="size"
        :total="total"
        @update:page="changePage"
        @update:size="changeSize"
      />
    </div>

    <!-- 角色修改：480 模态，单选三角色 -->
    <Modal
      :open="!!roleDialog"
      title="修改角色"
      :description="roleDialog ? `为 ${roleDialog.user.name} 选择新的角色` : ''"
      size="md"
      @close="roleDialog = null"
    >
      <div v-if="roleDialog" class="roles" role="radiogroup" aria-label="角色">
        <label
          v-for="role in ROLES"
          :key="role"
          class="roles__item"
          :class="{ 'roles__item--checked': roleDialog.next === role }"
        >
          <input v-model="roleDialog.next" type="radio" name="role" :value="role" class="roles__radio" />
          <span class="roles__body">
            <span class="roles__name">{{ ROLE_LABELS[role] }}</span>
            <span class="roles__hint">{{ ROLE_HINTS[role] }}</span>
          </span>
        </label>
      </div>
      <p class="roles__note">角色变更立即生效，该用户下次请求即按新角色鉴权。</p>
      <template #footer>
        <AppButton variant="secondary" :disabled="savingRole" @click="roleDialog = null">取消</AppButton>
        <AppButton
          variant="primary"
          :loading="savingRole"
          :disabled="!roleDialog || roleDialog.next === roleDialog.user.role"
          @click="saveRole"
        >
          保存角色
        </AppButton>
      </template>
    </Modal>

    <!-- 停用确认 -->
    <ConfirmDialog
      :open="!!statusTarget"
      title="停用账号"
      :description="
        statusTarget
          ? `停用后 ${statusTarget.name} 无法登录（含钉钉免登），名下任务保留。确认停用？`
          : ''
      "
      confirm-label="停用账号"
      tone="danger"
      :loading="savingStatus"
      @confirm="statusTarget && applyStatus(statusTarget, 'DISABLED')"
      @close="statusTarget = null"
    />

    <!-- 重置密码确认 -->
    <ConfirmDialog
      :open="!!resetTarget"
      title="重置密码"
      :description="
        resetTarget
          ? `将为 ${resetTarget.name} 生成一次性临时密码，其原密码立即失效，下次登录须修改密码。`
          : ''
      "
      confirm-label="生成临时密码"
      :loading="resetting"
      @confirm="doReset"
      @close="resetTarget = null"
    />

    <!-- 临时密码结果：仅显示一次 -->
    <Modal
      :open="!!temporary"
      title="临时密码已生成"
      :description="`请立即把临时密码告知 ${temporaryOwner}，关闭后无法再次查看。`"
      size="sm"
      :close-on-overlay="false"
      @close="closeTemporary"
    >
      <div class="temp">
        <code class="temp__value">{{ temporary }}</code>
        <AppButton variant="secondary" size="compact" @click="copyTemporary">复制</AppButton>
      </div>
      <p class="temp__note">该账号下次登录时会被要求修改密码；旧密码与旧会话均已失效。</p>
      <template #footer>
        <AppButton variant="primary" @click="closeTemporary">我已记录</AppButton>
      </template>
    </Modal>
  </div>
</template>

<style scoped>
.users__hint {
  margin: 6px 0 0;
  font-size: var(--fs-caption);
  color: var(--c-ink-500);
}
.filters {
  display: flex;
  align-items: center;
  gap: var(--sp-2);
  margin-bottom: var(--sp-3);
  flex-wrap: wrap;
}
.filters__search {
  width: 280px;
  max-width: 100%;
}
.users__pager {
  padding-top: var(--sp-3);
}

.who {
  display: inline-flex;
  align-items: center;
  gap: var(--sp-2);
  min-width: 0;
}
.who__avatar {
  width: 24px;
  height: 24px;
  flex: none;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  border-radius: 50%;
  background: var(--c-primary-soft);
  color: var(--c-primary);
  font-size: var(--fs-micro);
  font-weight: 600;
}
.who__name {
  font-weight: 500;
  color: var(--c-ink-900);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.bound {
  display: inline-flex;
}

.role {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  padding: 2px 4px 2px 2px;
  border: 1px solid transparent;
  border-radius: var(--r-sm);
  background: none;
  cursor: pointer;
  transition:
    border-color var(--t-hover) var(--ease),
    background var(--t-hover) var(--ease);
}
.role:hover {
  border-color: var(--c-line);
  background: var(--c-surface-hover);
}
.role__edit {
  font-size: var(--fs-micro);
  color: var(--c-ink-400);
}

/* 开关：启用=主色轨，停用=灰轨；disabled 用底色+灰字，不用 opacity */
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
  width: 28px;
  height: 16px;
  flex: none;
  border-radius: var(--r-pill);
  background: var(--c-line);
  transition: background var(--t-toggle) var(--ease);
}
.switch__thumb {
  position: absolute;
  top: 2px;
  left: 2px;
  width: 12px;
  height: 12px;
  border-radius: 50%;
  background: #fff;
  box-shadow: var(--sh-1);
  transition: transform var(--t-toggle) var(--ease);
}
.switch[aria-checked='true'] .switch__track {
  background: var(--c-primary);
}
.switch[aria-checked='true'] .switch__thumb {
  transform: translateX(12px);
}
.switch:disabled {
  cursor: not-allowed;
  color: var(--c-ink-400);
}
.switch:disabled .switch__track {
  background: var(--c-line-soft);
}

.roles {
  display: flex;
  flex-direction: column;
  gap: var(--sp-2);
}
.roles__item {
  display: flex;
  align-items: flex-start;
  gap: var(--sp-3);
  padding: var(--sp-3);
  border: 1px solid var(--c-line);
  border-radius: var(--r-md);
  cursor: pointer;
  transition:
    border-color var(--t-hover) var(--ease),
    background var(--t-hover) var(--ease);
}
.roles__item:hover {
  background: var(--c-surface-hover);
}
.roles__item--checked {
  border-color: var(--c-primary-border);
  background: var(--c-primary-soft);
}
.roles__radio {
  margin-top: 3px;
  accent-color: var(--c-primary);
}
.roles__body {
  display: flex;
  flex-direction: column;
  gap: 2px;
}
.roles__name {
  font-size: var(--fs-body);
  font-weight: 500;
  color: var(--c-ink-900);
}
.roles__hint {
  font-size: var(--fs-caption);
  color: var(--c-ink-500);
}
.roles__note {
  margin: var(--sp-3) 0 0;
  font-size: var(--fs-caption);
  color: var(--c-ink-500);
}

.temp {
  display: flex;
  align-items: center;
  gap: var(--sp-2);
  padding: var(--sp-3);
  border: 1px dashed var(--c-line);
  border-radius: var(--r-md);
  background: var(--c-surface-hover);
}
.temp__value {
  flex: 1;
  min-width: 0;
  font-family: var(--font-num);
  font-size: var(--fs-h3);
  letter-spacing: 0.06em;
  color: var(--c-ink-900);
  overflow-x: auto;
  white-space: nowrap;
}
.temp__note {
  margin: var(--sp-3) 0 0;
  font-size: var(--fs-caption);
  color: var(--c-ink-500);
}
</style>
