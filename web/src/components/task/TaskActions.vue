<script setup lang="ts">
/**
 * 详情页动作条（DESIGN §4.5 / §6）。
 * 显隐只看服务端 permissions：永不具备的动作不渲染；当前状态下不可用的动作渲染为禁用并写明原因。
 * 每个动作都走 useTaskActions，409 由它统一转成「正在刷新最新状态」。
 */
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import AppButton from '@/components/ui/AppButton.vue'
import AppDatePicker from '@/components/ui/AppDatePicker.vue'
import AppSelect from '@/components/ui/AppSelect.vue'
import AppTextarea from '@/components/ui/AppTextarea.vue'
import Modal from '@/components/ui/Modal.vue'
import ReasonDialog from '@/components/ui/ReasonDialog.vue'
import { useTaskActions, type TransitionAction } from '@/composables/useTaskActions'
import type { TaskDetail, UserOption } from '@/types/api'

const props = withDefaults(
  defineProps<{
    task: TaskDetail
    owners?: UserOption[]
    /** 编辑入口只给管理员（页面入口级判断；接口仍会拒） */
    canEdit?: boolean
  }>(),
  { owners: () => [], canEdit: false },
)

const emit = defineEmits<{ updated: [detail: TaskDetail]; stale: []; edit: [] }>()

const { run, busy } = useTaskActions({
  onUpdated: (detail) => emit('updated', detail),
  onConflict: () => emit('stale'),
})

const permissions = computed(() => props.task.permissions)
const finished = computed(() => props.task.status === 'DONE' || props.task.status === 'CANCELLED')

function reasonOf(key: string): string {
  return permissions.value?.disabledReasons?.[key] ?? ''
}

/* ---------------------------------------------------------------- 主按钮 */

interface PrimaryAction {
  action: TransitionAction
  label: string
  key: string
}

const primary = computed<PrimaryAction | null>(() => {
  switch (props.task.status) {
    case 'PENDING':
      return { action: 'start', label: '开始', key: 'canStart' }
    case 'IN_PROGRESS':
      return { action: 'submit', label: '提交验收', key: 'canSubmit' }
    case 'PENDING_ACCEPT':
      return { action: 'accept', label: '验收通过', key: 'canAccept' }
    default:
      return null
  }
})

const primaryEnabled = computed(() => {
  const action = primary.value
  if (!action) return false
  return Boolean((permissions.value as unknown as Record<string, boolean>)[action.key])
})

const primaryHint = computed(() => {
  const action = primary.value
  if (!action) return ''
  const key = action.action === 'start' ? 'start' : action.action === 'submit' ? 'submit' : 'accept'
  return reasonOf(key) || reasonOf(action.key)
})

/* ---------------------------------------------------------------- 更多菜单 */

interface MenuItem {
  key: string
  label: string
  action?: TransitionAction
}

const menuOpen = ref(false)
const menuRef = ref<HTMLElement | null>(null)

const menuItems = computed<MenuItem[]>(() => {
  const list: MenuItem[] = []
  const p = permissions.value
  if (!p) return list
  if (p.canCancel) list.push({ key: 'cancel', label: '取消任务', action: 'cancel' })
  if (p.canRestore) list.push({ key: 'restore', label: '恢复为待开始', action: 'restore' })
  if (p.canForceComplete) list.push({ key: 'forceComplete', label: '代确认完成', action: 'forceComplete' })
  if (p.canReassign) list.push({ key: 'reassign', label: '改派负责人', action: 'reassign' })
  if (p.canReschedule) list.push({ key: 'reschedule', label: '修改截止日期', action: 'reschedule' })
  return list
})

function toggleMenu(): void {
  menuOpen.value = !menuOpen.value
}

function onDocumentClick(event: MouseEvent): void {
  if (!menuOpen.value) return
  const root = menuRef.value
  if (root && event.target instanceof Node && !root.contains(event.target)) menuOpen.value = false
}

function onKeydown(event: KeyboardEvent): void {
  if (event.key === 'Escape') menuOpen.value = false
}

onMounted(() => {
  document.addEventListener('click', onDocumentClick)
  document.addEventListener('keydown', onKeydown)
})
onBeforeUnmount(() => {
  document.removeEventListener('click', onDocumentClick)
  document.removeEventListener('keydown', onKeydown)
})

/* ---------------------------------------------------------------- 提交验收 */

const submitOpen = ref(false)
const note = ref('')
const link = ref('')
const submitError = ref('')

watch(submitOpen, (open) => {
  if (!open) return
  note.value = ''
  link.value = ''
  submitError.value = ''
})

function openSubmit(): void {
  submitOpen.value = true
}

async function doSubmit(): Promise<void> {
  const trimmedLink = link.value.trim()
  if (trimmedLink && !/^https?:\/\/\S+$/i.test(trimmedLink)) {
    submitError.value = '交付链接需以 http:// 或 https:// 开头'
    return
  }
  submitError.value = ''
  const detail = await run(props.task.id, 'submit', {
    command: { completionNote: note.value.trim() || undefined, completionUrl: trimmedLink || undefined },
  })
  if (detail) submitOpen.value = false
}

/* ---------------------------------------------------------------- 原因类动作 */

type DialogKind = 'reject' | 'cancel' | 'forceComplete' | 'reassign' | 'reschedule'

interface DialogConfig {
  title: string
  description: string
  confirmLabel: string
  placeholder: string
}

const DIALOGS: Record<DialogKind, DialogConfig> = {
  reject: {
    title: '打回任务',
    description: '任务回到「进行中」，负责人会收到站内通知（钉钉已配置时同步推送）。',
    confirmLabel: '确认打回',
    placeholder: '写清哪里不符合要求，负责人照此修改',
  },
  cancel: {
    title: '取消任务',
    description: '取消后任务不再计入未完成，可随时由管理员恢复。',
    confirmLabel: '确认取消',
    placeholder: '说明取消原因，例如需求已撤销',
  },
  forceComplete: {
    title: '代确认完成',
    description: '适用于负责人离职或长期失联，将直接记为已完成并留痕。',
    confirmLabel: '确认完成',
    placeholder: '说明代确认的依据',
  },
  reassign: {
    title: '改派负责人',
    description: '任务状态不变，逾期判定立即按新负责人重算。',
    confirmLabel: '确认改派',
    placeholder: '说明改派原因',
  },
  reschedule: {
    title: '修改截止日期',
    description: '改期后逾期判定立即重算，无需等待定时任务。',
    confirmLabel: '确认改期',
    placeholder: '说明改期原因',
  },
}

const dialogKind = ref<DialogKind | null>(null)
const dialogError = ref('')
const nextOwnerId = ref<number | ''>('')
const nextDueDate = ref('')

const dialogConfig = computed(() => (dialogKind.value ? DIALOGS[dialogKind.value] : null))

const ownerOptions = computed(() =>
  (Array.isArray(props.owners) ? props.owners : [])
    .filter((owner) => owner.id !== props.task.owner?.id)
    .map((owner) => ({ label: owner.name, value: owner.id })),
)

function openDialog(kind: DialogKind): void {
  menuOpen.value = false
  dialogKind.value = kind
  dialogError.value = ''
  nextOwnerId.value = ''
  nextDueDate.value = ''
}

function closeDialog(): void {
  if (busy.value) return
  dialogKind.value = null
}

async function confirmDialog(reason: string): Promise<void> {
  const kind = dialogKind.value
  if (!kind) return
  if (kind === 'reassign' && nextOwnerId.value === '') {
    dialogError.value = '请选择新的负责人'
    return
  }
  if (kind === 'reschedule' && !nextDueDate.value) {
    dialogError.value = '请选择新的截止日期'
    return
  }
  dialogError.value = ''
  const payload =
    kind === 'reassign'
      ? { reason, ownerId: Number(nextOwnerId.value) }
      : kind === 'reschedule'
        ? { reason, dueDate: nextDueDate.value }
        : { reason }
  const detail = await run(props.task.id, kind, payload)
  if (detail) dialogKind.value = null
}

function onMenuAction(item: MenuItem): void {
  if (!item.action) return
  if (item.action === 'restore') {
    void run(props.task.id, 'restore')
    menuOpen.value = false
    return
  }
  openDialog(item.action as DialogKind)
}
</script>

<template>
  <div class="actions">
    <template v-if="finished">
      <AppButton variant="secondary" disabled title="任务已结束">任务已结束</AppButton>
    </template>

    <template v-else>
      <AppButton
        v-if="primary"
        variant="primary"
        :disabled="!primaryEnabled"
        :loading="busy === primary.action"
        :title="primaryHint || undefined"
        @click="primary.action === 'submit' ? openSubmit() : run(task.id, primary.action)"
      >
        {{ primary.label }}
      </AppButton>

      <AppButton
        v-if="permissions?.canReject"
        variant="danger-outline"
        @click="openDialog('reject')"
      >
        打回
      </AppButton>
    </template>

    <!-- 已结束的任务仍然需要「恢复」（已取消时管理员可恢复），所以菜单不随主按钮一起隐藏 -->
    <div v-if="menuItems.length || (canEdit && !finished)" ref="menuRef" class="actions__more">
      <AppButton
        variant="secondary"
        :aria-expanded="menuOpen"
        aria-haspopup="menu"
        @click="toggleMenu"
      >
        更多<span class="actions__caret" aria-hidden="true">▾</span>
      </AppButton>
      <div v-if="menuOpen" class="menu" role="menu">
        <button
          v-for="item in menuItems"
          :key="item.key"
          type="button"
          role="menuitem"
          class="menu__item"
          :class="{ 'is-danger': item.key === 'cancel' }"
          @click="onMenuAction(item)"
        >
          {{ item.label }}
        </button>
        <button
          v-if="canEdit && !finished"
          type="button"
          role="menuitem"
          class="menu__item"
          @click="menuOpen = false; emit('edit')"
        >
          编辑任务
        </button>
      </div>
    </div>

    <p v-if="primary && !primaryEnabled && primaryHint" class="actions__hint">{{ primaryHint }}</p>

    <Modal
      :open="submitOpen"
      title="提交验收"
      description="填写完成说明与交付链接，派发人会收到待验收提醒。"
      size="md"
      @close="submitOpen = false"
    >
      <div class="submit-form">
        <AppTextarea
          v-model="note"
          label="完成说明"
          :rows="4"
          :maxlength="500"
          placeholder="做到了什么、还差什么（选填）"
        />
        <AppTextarea
          v-model="link"
          label="交付链接"
          :rows="2"
          :maxlength="500"
          placeholder="https://…（选填）"
          :error="submitError"
        />
      </div>
      <template #footer>
        <AppButton variant="secondary" :disabled="busy === 'submit'" @click="submitOpen = false">
          取消
        </AppButton>
        <AppButton
          variant="primary"
          :loading="busy === 'submit'"
          loading-text="提交中"
          @click="doSubmit"
        >
          提交验收
        </AppButton>
      </template>
    </Modal>

    <ReasonDialog
      :open="dialogKind !== null"
      :title="dialogConfig?.title ?? ''"
      :description="dialogConfig?.description"
      :confirm-label="dialogConfig?.confirmLabel"
      :placeholder="dialogConfig?.placeholder"
      :loading="busy !== ''"
      :error="dialogError"
      @close="closeDialog"
      @confirm="confirmDialog"
    >
      <template #extra>
        <AppSelect
          v-if="dialogKind === 'reassign'"
          :model-value="nextOwnerId"
          :options="ownerOptions"
          label="新负责人"
          required
          placeholder="选择负责人"
          @update:model-value="nextOwnerId = $event ? Number($event) : ''; dialogError = ''"
        />
        <AppDatePicker
          v-if="dialogKind === 'reschedule'"
          :model-value="nextDueDate"
          label="新截止日期"
          required
          @update:model-value="nextDueDate = $event; dialogError = ''"
        />
      </template>
    </ReasonDialog>
  </div>
</template>

<style scoped>
.actions {
  position: relative;
  display: flex;
  align-items: center;
  gap: var(--sp-2);
  flex-wrap: wrap;
  justify-content: flex-end;
}
.actions__more {
  position: relative;
}
.actions__caret {
  margin-left: var(--sp-1);
  font-size: var(--fs-micro);
  color: var(--c-ink-400);
}
.actions__hint {
  flex-basis: 100%;
  margin: 0;
  text-align: right;
  font-size: var(--fs-caption);
  color: var(--c-ink-500);
}
.menu {
  position: absolute;
  right: 0;
  top: calc(100% + var(--sp-1));
  z-index: var(--z-dropdown);
  min-width: 168px;
  padding: var(--sp-1);
  border: 1px solid var(--c-line);
  border-radius: var(--r-lg);
  background: var(--c-surface);
  box-shadow: var(--sh-2);
}
.menu__item {
  display: block;
  width: 100%;
  padding: var(--sp-2) var(--sp-3);
  border: 0;
  border-radius: var(--r-sm);
  background: transparent;
  text-align: left;
  font-size: var(--fs-body-sm);
  color: var(--c-ink-900);
  cursor: pointer;
}
.menu__item:hover,
.menu__item:focus-visible {
  background: var(--c-surface-hover);
  outline: none;
}
.menu__item.is-danger {
  color: var(--c-danger-text);
}
.menu__item.is-danger:hover {
  background: var(--c-danger-soft);
}
.submit-form {
  display: flex;
  flex-direction: column;
  gap: var(--sp-4);
}
</style>
