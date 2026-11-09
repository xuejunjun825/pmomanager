<script setup lang="ts">
/**
 * 通知中心（DESIGN §4.10 / AC-13）。
 *
 * 三条硬约束：
 * 1. 只展示服务端给的通知——渠道状态、文案、已读标记都不在前端推断；
 * 2. 点击一条 = 先置已读再跳任务详情，避免「看了但角标没减」；
 * 3. 「钉钉未配置 / 未绑定钉钉」是中性事实，不是错误，绝不红色（AC-21）。
 */
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import AppButton from '@/components/ui/AppButton.vue'
import ChannelChip from '@/components/ui/ChannelChip.vue'
import ConfirmDialog from '@/components/ui/ConfirmDialog.vue'
import EmptyState from '@/components/ui/EmptyState.vue'
import ErrorState from '@/components/ui/ErrorState.vue'
import Modal from '@/components/ui/Modal.vue'
import PageHeader from '@/components/ui/PageHeader.vue'
import Pagination from '@/components/ui/Pagination.vue'
import { notificationsApi } from '@/api/notifications'
import { toApiError, type ApiError } from '@/api/http'
import { useNotificationsStore } from '@/stores/notifications'
import { useToast } from '@/composables/useToast'
import { relativeTime } from '@/utils/format'
import { NOTIFY_TYPE_LABELS, type NotifyType } from '@/types/enums'
import type { NotificationRow } from '@/types/api'

const router = useRouter()
const store = useNotificationsStore()
const toast = useToast()

const items = ref<NotificationRow[]>([])
const total = ref(0)
const page = ref(1)
const size = ref(20)
const unreadOnly = ref(false)
const unread = ref(0)
const allTotal = ref<number | null>(null)
const loading = ref(false)
const error = ref<ApiError | null>(null)
const confirmAll = ref(false)
const markingAll = ref(false)
const channelNote = ref<NotificationRow | null>(null)

const hasUnread = computed(() => unread.value > 0)

function typeText(item: NotificationRow): string {
  return item.typeLabel || NOTIFY_TYPE_LABELS[item.type as NotifyType] || '通知'
}

async function load(): Promise<void> {
  loading.value = true
  error.value = null
  try {
    const data = await notificationsApi.list({
      unreadOnly: unreadOnly.value,
      page: page.value,
      size: size.value,
    })
    items.value = data.items ?? []
    total.value = data.total ?? 0
    unread.value = data.unreadCount ?? 0
    store.setUnread(unread.value)
    if (!unreadOnly.value) allTotal.value = total.value
  } catch (e) {
    error.value = toApiError(e)
  } finally {
    loading.value = false
  }
}

function switchTab(next: boolean): void {
  if (unreadOnly.value === next) return
  unreadOnly.value = next
  page.value = 1
  void load()
}

function changePage(next: number): void {
  page.value = next
  void load()
}

function changeSize(next: number): void {
  size.value = next
  page.value = 1
  void load()
}

/** 置已读：乐观更新 + 失败回滚，角标与列表始终同源 */
async function markRead(item: NotificationRow): Promise<boolean> {
  if (item.read) return true
  item.read = true
  try {
    await notificationsApi.markRead(item.id)
    unread.value = Math.max(0, unread.value - 1)
    store.decrement()
    return true
  } catch (e) {
    item.read = false
    const err = toApiError(e)
    toast.error(err.status === 404 ? '该通知已不存在' : err.message || '标记已读失败')
    return false
  }
}

async function openItem(item: NotificationRow): Promise<void> {
  await markRead(item)
  if (item.taskId) {
    void router.push(`/tasks/${item.taskId}`)
    return
  }
  toast.info('该通知没有关联任务，已标为已读')
}

async function markAll(): Promise<void> {
  markingAll.value = true
  try {
    const { updated } = await notificationsApi.markAllRead()
    store.setUnread(0)
    unread.value = 0
    confirmAll.value = false
    toast.success(updated > 0 ? `已将 ${updated} 条通知标为已读` : '当前没有未读通知')
    await load()
  } catch (e) {
    toast.error(toApiError(e).message || '操作失败，请重试')
  } finally {
    markingAll.value = false
  }
}

onMounted(() => {
  void load()
})
</script>

<template>
  <div class="notices">
    <PageHeader title="通知中心" description="逾期提醒、催办与验收打回都会出现在这里。">
      <template #actions>
        <AppButton variant="ghost" size="compact" :disabled="!hasUnread" @click="confirmAll = true">
          全部标为已读
        </AppButton>
      </template>
    </PageHeader>

    <section class="notices__card" aria-label="通知列表">
      <div class="tabs" role="tablist" aria-label="通知筛选">
        <button
          type="button"
          class="tabs__item"
          role="tab"
          :aria-selected="!unreadOnly"
          :class="{ 'tabs__item--active': !unreadOnly }"
          @click="switchTab(false)"
        >
          全部
          <span v-if="allTotal !== null" class="tabs__count num">{{ allTotal }}</span>
        </button>
        <button
          type="button"
          class="tabs__item"
          role="tab"
          :aria-selected="unreadOnly"
          :class="{ 'tabs__item--active': unreadOnly }"
          @click="switchTab(true)"
        >
          未读
          <span v-if="unread > 0" class="tabs__count num">{{ unread }}</span>
        </button>
      </div>

      <div v-if="loading" class="notices__skeleton" aria-busy="true" aria-live="polite">
        <span class="visually-hidden">正在加载通知</span>
        <div v-for="n in 6" :key="`sk-${n}`" class="notices__sk-row">
          <span class="sk-block notices__sk-line" />
          <span class="sk-block notices__sk-chip" />
        </div>
      </div>

      <ErrorState
        v-else-if="error"
        description="通知加载失败，请检查网络后重试。"
        :trace-id="error.traceId"
        @retry="load()"
      />

      <EmptyState
        v-else-if="!items.length"
        :title="unreadOnly ? '全部已读，干得漂亮' : '暂无通知'"
        :description="
          unreadOnly ? '新通知到达后会出现在这里。' : '任务逾期、被催办或被验收打回时，系统会在这里提醒你。'
        "
        size="sm"
      />

      <ul v-else class="notices__list">
        <li
          v-for="item in items"
          :key="item.id"
          class="notice"
          :class="{ 'notice--unread': !item.read }"
        >
          <button
            type="button"
            class="notice__hit"
            :aria-label="`${typeText(item)}：${item.taskTitle || '系统通知'}${item.read ? '' : '（未读）'}`"
            @click="openItem(item)"
          >
            <span class="notice__title">
              <span class="notice__type">{{ typeText(item) }}</span>
              <span v-if="item.taskTitle" class="notice__task">{{ item.taskTitle }}</span>
              <span v-else class="notice__task notice__task--muted">系统通知</span>
            </span>
            <span class="notice__sub">
              <template v-if="item.ownerName">负责人 {{ item.ownerName }}</template>
              <template v-else>无需处理，仅供知悉</template>
            </span>
          </button>

          <span class="notice__side">
            <ChannelChip
              :status="item.channelStatus"
              :label="item.channelLabel"
              interactive
              @select="channelNote = item"
            />
            <time class="notice__time num" :datetime="item.createdAt">{{ relativeTime(item.createdAt) }}</time>
            <span v-if="!item.read" class="notice__dot" aria-label="未读" />
          </span>
        </li>
      </ul>

      <div v-if="!loading && !error && total > 0" class="notices__pager">
        <Pagination
          :page="page"
          :size="size"
          :total="total"
          @update:page="changePage"
          @update:size="changeSize"
        />
      </div>
    </section>

    <ConfirmDialog
      :open="confirmAll"
      title="全部标为已读"
      description="将把当前所有未读通知标记为已读，此操作不可撤销。"
      confirm-label="全部标为已读"
      :loading="markingAll"
      @confirm="markAll"
      @close="confirmAll = false"
    />

    <Modal
      :open="!!channelNote"
      title="钉钉推送失败"
      description="站内通知已送达，不受影响。"
      size="sm"
      @close="channelNote = null"
    >
      <div class="retry">
        <p class="retry__lead">系统会按 1 / 5 / 15 分钟退避重试，最多 3 次；仍失败则本条保持「钉钉失败」。</p>
        <ul class="retry__list">
          <li>若该用户未绑定钉钉，请让其在钉钉侧完成绑定。</li>
          <li>若持续失败，请联系管理员检查钉钉应用配置。</li>
          <li>无论推送结果如何，站内通知都不会丢失。</li>
        </ul>
        <p v-if="channelNote" class="retry__meta">
          通知 #{{ channelNote.id }} · {{ channelNote.taskTitle || '系统通知' }}
        </p>
        <p v-if="channelNote" class="retry__meta">
          已尝试 {{ channelNote.retryCount ?? 0 }} 次<template v-if="channelNote.failureReason">，最近失败原因：{{ channelNote.failureReason }}</template>
        </p>
      </div>
      <template #footer>
        <AppButton variant="secondary" @click="channelNote = null">知道了</AppButton>
      </template>
    </Modal>
  </div>
</template>

<style scoped>
.notices__card {
  background: var(--c-surface);
  border: 1px solid var(--c-line);
  border-radius: var(--r-lg);
  box-shadow: var(--sh-1);
  overflow: hidden;
}
.tabs {
  display: flex;
  align-items: center;
  gap: var(--sp-1);
  padding: 0 var(--sp-4);
  border-bottom: 1px solid var(--c-line);
  background: var(--c-surface);
}
.tabs__item {
  position: relative;
  display: inline-flex;
  align-items: center;
  gap: 6px;
  height: 48px;
  padding: 0 var(--sp-2);
  border: 0;
  background: none;
  color: var(--c-ink-500);
  font-family: inherit;
  font-size: var(--fs-body);
  font-weight: 500;
  cursor: pointer;
  transition: color var(--t-hover) var(--ease);
}
.tabs__item:hover {
  color: var(--c-ink-900);
}
.tabs__item--active {
  color: var(--c-ink-900);
}
.tabs__item--active::after {
  content: '';
  position: absolute;
  left: var(--sp-2);
  right: var(--sp-2);
  bottom: -1px;
  height: 2px;
  background: var(--c-primary);
  border-radius: 2px 2px 0 0;
}
.tabs__count {
  min-width: 20px;
  padding: 0 5px;
  height: 18px;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  border-radius: var(--r-pill);
  background: var(--c-line-soft);
  color: var(--c-ink-600);
  font-size: var(--fs-micro);
  font-weight: 600;
}
.tabs__item--active .tabs__count {
  background: var(--c-primary-soft);
  color: var(--c-primary);
}

.notices__list {
  list-style: none;
  margin: 0;
  padding: 0;
}
.notice {
  position: relative;
  display: grid;
  grid-template-columns: minmax(0, 1fr) auto;
  align-items: center;
  gap: var(--sp-4);
  min-height: 72px;
  padding: 0 var(--sp-4) 0 calc(var(--sp-4) + 3px);
  border-bottom: 1px solid var(--c-line-soft);
  transition: background var(--t-hover) var(--ease);
}
.notice:last-child {
  border-bottom: 0;
}
.notice:hover {
  background: var(--c-surface-hover);
}
.notice--unread::before {
  content: '';
  position: absolute;
  left: 0;
  top: 0;
  bottom: 0;
  width: 3px;
  background: var(--c-primary);
}
.notice__hit {
  display: flex;
  flex-direction: column;
  gap: 4px;
  min-width: 0;
  padding: 12px 0;
  border: 0;
  background: none;
  text-align: left;
  font-family: inherit;
  cursor: pointer;
  color: inherit;
}
.notice__title {
  display: flex;
  align-items: baseline;
  gap: var(--sp-2);
  min-width: 0;
  font-size: var(--fs-body);
  line-height: var(--lh-body);
  color: var(--c-ink-900);
}
.notice--unread .notice__title {
  font-weight: 600;
}
.notice__type {
  flex: none;
  color: var(--c-ink-700);
  font-weight: inherit;
}
.notice__task {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.notice__task--muted {
  color: var(--c-ink-500);
}
.notice__sub {
  font-size: var(--fs-body-sm);
  line-height: var(--lh-body-sm);
  color: var(--c-ink-500);
}
.notice__side {
  display: flex;
  align-items: center;
  gap: var(--sp-3);
  flex: none;
}
.notice__time {
  font-size: var(--fs-caption);
  color: var(--c-ink-500);
  white-space: nowrap;
  min-width: 76px;
  text-align: right;
}
.notice__dot {
  width: 8px;
  height: 8px;
  border-radius: 50%;
  background: var(--c-primary);
  flex: none;
}

.notices__skeleton {
  padding: var(--sp-2) 0;
}
.notices__sk-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--sp-4);
  height: 72px;
  padding: 0 var(--sp-4);
  border-bottom: 1px solid var(--c-line-soft);
}
.notices__sk-line {
  height: 12px;
  width: 42%;
  border-radius: var(--r-sm);
}
.notices__sk-chip {
  height: 20px;
  width: 72px;
  border-radius: var(--r-sm);
}
.notices__pager {
  padding: var(--sp-3) var(--sp-4);
  border-top: 1px solid var(--c-line);
  background: var(--c-surface);
}

.retry__lead {
  margin: 0 0 var(--sp-3);
  font-size: var(--fs-body-sm);
  color: var(--c-ink-700);
}
.retry__list {
  margin: 0;
  padding-left: 18px;
  font-size: var(--fs-body-sm);
  color: var(--c-ink-500);
  line-height: 1.7;
}
.retry__meta {
  margin: var(--sp-4) 0 0;
  font-size: var(--fs-caption);
  color: var(--c-ink-400);
}

@media (max-width: 720px) {
  .notice {
    grid-template-columns: minmax(0, 1fr);
    gap: 0;
    padding-top: 4px;
    padding-bottom: 10px;
  }
  .notice__side {
    padding-left: 0;
    gap: var(--sp-2);
  }
  .notice__time {
    min-width: 0;
    text-align: left;
  }
}
</style>
