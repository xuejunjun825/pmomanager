/**
 * HTTP 契约类型（TECHNICAL §5）。页面只消费这里的形状，不自行拼装。
 */
import type {
  ChannelStatus,
  JobKey,
  MatrixRange,
  NotifyType,
  Priority,
  Role,
  SourceForm,
  SourceType,
  TaskStatus,
  UserStatus,
} from './enums'

/* ---------------------------------------------------------------- 通用 */

export interface FieldError {
  field: string
  message: string
  /** 仅决议项批量保存时出现，用于定位行 */
  index?: number
}

export interface ApiErrorBody {
  code: string
  message: string
  fieldErrors?: FieldError[]
  details?: Record<string, unknown>
  traceId?: string
}

export interface PageResponse<T> {
  items: T[]
  total: number
  page: number
  size: number
}

export interface PageQuery {
  page?: number
  size?: number
}

export interface UserRef {
  id: number
  name: string
  role?: Role
}

/* ---------------------------------------------------------------- 认证 */

export interface AuthConfig {
  dingtalkConfigured: boolean
  missingKeys: string[]
  passwordLogin: boolean
  siteName: string
}

export interface AuthUser {
  id: number
  name: string
  role: Role
  avatarUrl?: string | null
  mustChangePassword: boolean
  /** 服务端给出的落地页（前端 landingPath 为兜底） */
  landingPath?: string
}

export interface LoginRequest {
  username: string
  password: string
}

export interface ChangePasswordRequest {
  oldPassword: string
  newPassword: string
}

export interface AuthorizeUrlResponse {
  authorizeUrl: string
}

export interface DingTalkAutoRequest {
  authCode: string
  next?: string
}

/* ---------------------------------------------------------------- 用户 */

export interface UserRow {
  id: number
  username: string
  name: string
  role: Role
  status: UserStatus
  dingtalkBound: boolean
  mustChangePassword: boolean
  createdAt?: string
  /** users.last_login_at；从未登录为 null */
  lastLoginAt?: string | null
}

export interface UserOption {
  id: number
  name: string
  role: Role
  status: UserStatus
}

export interface UserPageQuery extends PageQuery {
  keyword?: string
  role?: Role | ''
  status?: UserStatus | ''
}

/* ---------------------------------------------------------------- 会议 */

export interface MeetingRow {
  id: number
  title: string
  meetingDate: string
  host: string
  archived: boolean
  taskCount: number
  unfinishedCount: number
  closed: boolean
}

export interface DecisionItemRow {
  id: number
  description: string
  owner: UserRef
  dueDate: string
  deliveryNote?: string | null
  sortNo: number
  converted: boolean
  taskId?: number | null
  taskStatus?: TaskStatus | null
  isOverdue: boolean
  overdueDays: number
}

export interface MeetingDetail extends MeetingRow {
  attendees: UserRef[]
  note?: string | null
  items: DecisionItemRow[]
}

export interface MeetingRequest {
  title: string
  meetingDate: string
  host: string
  attendeeIds: number[]
  note?: string
}

export interface DecisionItemRequest {
  description: string
  ownerId: number | null
  dueDate: string
  deliveryNote?: string
}

export interface ConvertResult {
  created: Array<{ itemId: number; taskId: number }>
  skipped: Array<{ itemId: number; reason: string; taskId?: number | null }>
}

/* ---------------------------------------------------------------- 任务 */

/**
 * 任务来源读模型（TECHNICAL §3.4）。名称与三色由后端 LEFT JOIN source_types 下发，
 * 前端不再按 code 分支；缺字段（老接口/夹具）时标签回落 slate 与「未知来源（CODE）」。
 */
export interface TaskSource {
  type: SourceType
  typeLabel?: string
  color?: string | null
  softColor?: string | null
  borderColor?: string | null
  meetingId?: number | null
  meetingTitle?: string | null
  decisionItemId?: number | null
  note?: string | null
}

export interface TaskRow {
  id: number
  title: string
  owner: UserRef
  dueDate: string
  source: TaskSource
  status: TaskStatus
  statusLabel?: string
  /** 服务端算好，前端不推算（AC-11 / AC-17） */
  isOverdue: boolean
  overdueDays: number
  dueSoon: boolean
  urgeCount: number
  lastUrgedAt?: string | null
  updatedAt: string
  completedAt?: string | null
}

export interface TaskEvent {
  action: string
  actionLabel: string
  actor: UserRef
  fromStatus?: TaskStatus | null
  toStatus?: TaskStatus | null
  reason?: string | null
  createdAt: string
}

export interface TaskPermissions {
  canStart: boolean
  canSubmit: boolean
  canAccept: boolean
  canReject: boolean
  canCancel: boolean
  canRestore: boolean
  canForceComplete: boolean
  canReassign: boolean
  canReschedule: boolean
  canUrge: boolean
  /** 可追加进度（管理员/管理层/负责人/被@），与 canView 同一口径（TECHNICAL §3.7） */
  canAddProgress?: boolean
  /** 仅被@可见的只读视角：页面整组隐藏状态动作，接口侧仍 403 兜底（AC-8） */
  mentionOnly?: boolean
  /** 键为动作名，值为禁用原因（服务端给出，前端直接展示） */
  disabledReasons: Record<string, string>
}

export interface TaskDetail extends TaskRow {
  dispatcher: UserRef
  priority: Priority
  completionNote?: string | null
  completionUrl?: string | null
  submittedAt?: string | null
  events: TaskEvent[]
  permissions: TaskPermissions
}

export interface TaskFilter extends PageQuery {
  status?: TaskStatus | ''
  ownerId?: number | ''
  sourceType?: SourceType | ''
  meetingId?: number | ''
  overdue?: boolean | ''
  keyword?: string
  sort?: 'due_asc' | 'updated_desc' | ''
}

export interface CreateTaskRequest {
  title: string
  ownerId: number | null
  dueDate: string
  sourceType: SourceType
  sourceMeetingId?: number | null
  sourceDecisionItemId?: number | null
  sourceNote?: string
  priority?: Priority
}

export interface UpdateTaskRequest {
  title?: string
  ownerId?: number
  dueDate?: string
  priority?: Priority
  sourceNote?: string
}

export interface SubmitCommand {
  completionNote?: string
  completionUrl?: string
}

export interface UrgeResult {
  urgeCount: number
  lastUrgedAt: string
  channelStatus: ChannelStatus
  channelLabel: string
  message: string
}

/* ---------------------------------------------------------------- 通知 */

export interface NotificationRow {
  id: number
  type: NotifyType
  typeLabel: string
  taskId?: number | null
  taskTitle?: string | null
  ownerName?: string | null
  createdAt: string
  channelStatus: ChannelStatus
  channelLabel: string
  read: boolean
  /** 钉钉已尝试次数 */
  retryCount: number
  /** MENTION 通知对应的进度 id，用于深链 #progress-{id}（不解析 dedup_key） */
  progressId?: number | null
  /** 最近一次失败原因；成功/未失败时为 null */
  failureReason?: string | null
}

export interface NotificationPage extends PageResponse<NotificationRow> {
  unreadCount: number
}

/* ---------------------------------------------------------------- 矩阵 / 看板 */

export interface MatrixRow {
  userId: number
  name: string
  role: Role
  userStatus: UserStatus
  pending: number
  inProgress: number
  pendingAccept: number
  overdue: number
  done: number
  cancelled: number
  total: number
  /** 0 分母时为 null，前端显示 — */
  completionRate: number | null
}

export interface MatrixView {
  today: string
  /** range=ALL 时后端显式下发 null（页面显示「全部时间」），不要按空串处理 */
  rangeFrom: string | null
  rangeTo: string | null
  rows: MatrixRow[]
  totals: MatrixRow
}

export interface MatrixQuery {
  sourceType?: SourceType | ''
  range?: MatrixRange
  includeDisabled?: boolean
}

export type MatrixBucket = 'pending' | 'inProgress' | 'pendingAccept' | 'overdue' | 'done' | 'cancelled'

export interface DashboardKpis {
  total: number
  unfinished: number
  overdue: number
  overdueRate: number
  dueSoon24h: number
  pendingAccept: number
}

export interface DashboardView {
  today: string
  kpis: DashboardKpis
  focus: TaskRow[]
  focusTotal: number
}

export interface DashboardQuery {
  ownerId?: number | ''
  sourceType?: SourceType | ''
  meetingId?: number | ''
  focusLimit?: number
}

/* ---------------------------------------------------------------- 设置 */

export interface ReportSettings {
  dailyEnabled: boolean
  dailyTime: string
  weeklyEnabled: boolean
  weeklyTime: string
  recipients: UserRef[]
  recipientsEmpty: boolean
}

export interface SettingsView {
  report: ReportSettings
  dingtalk: { configured: boolean; missingKeys: string[] }
  updatedAt?: string | null
}

export interface UpdateReportSettingsRequest {
  dailyEnabled: boolean
  dailyTime: string
  weeklyEnabled: boolean
  weeklyTime: string
  recipientIds: number[]
}

/* ---------------------------------------------------------------- 来源配置 */

/** 来源下拉项（meta.sourceTypes）：同时驱动下拉、筛选与标签颜色，一份数据三处复用（AC-5） */
export interface SourceTypeOption {
  value: SourceType
  label: string
  color: string
  softColor: string
  borderColor: string
  form: SourceForm
  enabled: boolean
  sort: number
}

/** 设置页来源表格行（SourceTypeView）：code 是业务主键，表单里只读展示 */
export interface SourceTypeRow {
  code: SourceType
  name: string
  form: SourceForm
  color: string
  softColor: string
  borderColor: string
  sort: number
  enabled: boolean
  builtin: boolean
  locked: boolean
  usageCount: number
}

/** 新增 / 编辑来源请求体（编码由后端生成，编辑时不可改） */
export interface SourceTypePayload {
  name: string
  form: SourceForm
  color: string
  sort?: number | null
}

/** 8 组色板条目（后端下发，前端不硬编码色值） */
export interface SourcePaletteEntry {
  color: string
  soft: string
  border: string
  name: string
}

/* ---------------------------------------------------------------- 进度 */

export interface ProgressRow {
  id: number
  content: string
  author: UserRef
  mentions: UserRef[]
  /** 这条进度是否 @ 了我 */
  mentionedMe: boolean
  createdAt: string
}

export interface ProgressPage {
  items: ProgressRow[]
  /** 全部进度条数，同时驱动 200 条上限的禁用态 */
  total: number
  hasMore: boolean
}

export interface AddProgressPayload {
  content: string
  mentionedUserIds?: number[]
}

/* ---------------------------------------------------------------- 元数据 */

export interface MetaOptions {
  owners: UserOption[]
  meetings: Array<{ id: number; title: string; meetingDate?: string }>
  sourceTypes: SourceTypeOption[]
  statuses: Array<{ value: TaskStatus; label: string }>
  priorities: Array<{ value: Priority; label: string }>
}

/** 后端 job 执行记录（仅排障页使用，前端不常驻） */
export interface JobRunRow {
  jobKey: JobKey
  runDate: string
  finishedAt?: string | null
  detail?: string | null
}
