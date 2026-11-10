/**
 * 枚举取值与展示词（TECHNICAL §3.3；DESIGN §9 固定文案）
 * 库值 ↔ 前端类型只此一套，展示词只在这里定义一次。
 */

export type Role = 'ADMIN' | 'OWNER' | 'EXEC'

export const ROLES: readonly Role[] = ['ADMIN', 'OWNER', 'EXEC'] as const

export const ROLE_LABELS: Record<Role, string> = {
  ADMIN: '管理员',
  OWNER: '任务负责人',
  EXEC: '管理层',
}

export const ROLE_SHORT_LABELS: Record<Role, string> = {
  ADMIN: '管理员',
  OWNER: '负责人',
  EXEC: '管理层',
}

export function isRole(value: unknown): value is Role {
  return value === 'ADMIN' || value === 'OWNER' || value === 'EXEC'
}

export type TaskStatus = 'PENDING' | 'IN_PROGRESS' | 'PENDING_ACCEPT' | 'DONE' | 'CANCELLED'

export const TASK_STATUS_LABELS: Record<TaskStatus, string> = {
  PENDING: '待开始',
  IN_PROGRESS: '进行中',
  PENDING_ACCEPT: '待验收',
  DONE: '已完成',
  CANCELLED: '已取消',
}

/** 已结束（不计入未完成、不参与逾期判定） */
export const FINISHED_STATUSES: readonly TaskStatus[] = ['DONE', 'CANCELLED'] as const

export function isFinishedStatus(status: TaskStatus | string | null | undefined): boolean {
  return status === 'DONE' || status === 'CANCELLED'
}

/**
 * 任务来源（TECHNICAL §3.4）：编码由后端 `source_types` 配置表下发，前端不再有固定三类。
 * 名称与三色一律来自接口（任务行的 source / meta.sourceTypes），本地不保留第二套显示口径。
 */
export type SourceType = string

/**
 * 来源形态（有限业务枚举，不是可配置数据）：决定建任务时绑定会议、绑定项目还是自由说明。
 * `PROJECT` 只在形态层出现，与历史来源编码 `PROJECT`（项目任务）同名不同义：
 * 判定形态一律读 `source_types.form`，绝不按编码猜（r4 D-01）。
 */
export type SourceForm = 'MEETING' | 'PROJECT' | 'NOTE'

export const SOURCE_FORM_LABELS: Record<SourceForm, string> = {
  MEETING: '需绑定会议',
  PROJECT: '需绑定项目',
  NOTE: '自由说明',
}

export type UserStatus = 'ACTIVE' | 'DISABLED'

export const USER_STATUS_LABELS: Record<UserStatus, string> = {
  ACTIVE: '启用',
  DISABLED: '已停用',
}

export type Priority = 'HIGH' | 'MEDIUM' | 'LOW'

export const PRIORITY_LABELS: Record<Priority, string> = {
  HIGH: '高',
  MEDIUM: '中',
  LOW: '低',
}

export type NotifyType =
  | 'DUE_SOON'
  | 'OVERDUE'
  | 'URGE'
  | 'REJECTED'
  | 'REASSIGNED'
  | 'RESCHEDULED'
  | 'MENTION'
  | 'DAILY_REPORT'
  | 'WEEKLY_REPORT'

export const NOTIFY_TYPE_LABELS: Record<NotifyType, string> = {
  DUE_SOON: '即将到期',
  OVERDUE: '逾期提醒',
  URGE: '催办',
  REJECTED: '验收打回',
  REASSIGNED: '任务改派',
  RESCHEDULED: '截止改期',
  MENTION: '被@',
  DAILY_REPORT: '督办日报',
  WEEKLY_REPORT: '督办周报',
}

/**
 * 钉钉渠道状态（TECHNICAL §3.3 DingTalkStatus）。
 * 与 DESIGN §9 五个固定文案一一对应；页面以服务端 channelLabel 为准，此处仅作兜底。
 */
export type ChannelStatus = 'PENDING' | 'SENT' | 'FAILED' | 'NOT_CONFIGURED' | 'UNBOUND' | 'SKIPPED'

export const CHANNEL_LABELS: Record<ChannelStatus, string> = {
  PENDING: '待发送',
  SENT: '钉钉已送达',
  FAILED: '钉钉失败',
  NOT_CONFIGURED: '钉钉未配置',
  UNBOUND: '未绑定钉钉',
  SKIPPED: '站内',
}

export type BadgeTone = 'neutral' | 'primary' | 'success' | 'warning' | 'danger' | 'info' | 'purple'

export const CHANNEL_TONES: Record<ChannelStatus, BadgeTone> = {
  PENDING: 'neutral',
  SENT: 'success',
  FAILED: 'danger',
  NOT_CONFIGURED: 'neutral',
  UNBOUND: 'neutral',
  SKIPPED: 'neutral',
}

export type JobKey = 'DAILY_SCAN' | 'DAILY_REPORT' | 'WEEKLY_REPORT'

export type MatrixRange = 'WEEK' | 'MONTH' | 'ALL'
