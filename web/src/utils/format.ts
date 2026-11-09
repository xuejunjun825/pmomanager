/**
 * 展示格式化（DESIGN §1.6 / §9）：日期、时间、数字、百分比、渠道文案。
 * 只做格式化，不做任何「今天 / 逾期」推算——那是服务端 is_overdue 的职责。
 */
import { CHANNEL_LABELS, type ChannelStatus } from '@/types/enums'

const pad2 = (value: number): string => String(value).padStart(2, '0')

/** 兼容 `YYYY-MM-DD`、`YYYY-MM-DDTHH:mm:ss`、ISO 带时区三种输入 */
export function parseDateTime(value?: string | null): Date | null {
  if (!value) return null
  const text = String(value).trim()
  if (!text) return null
  if (/^\d{4}-\d{2}-\d{2}$/.test(text)) {
    const [year, month, day] = text.split('-').map(Number)
    return new Date(year, month - 1, day)
  }
  const parsed = new Date(text)
  return Number.isNaN(parsed.getTime()) ? null : parsed
}

/** 日期一律 YYYY-MM-DD */
export function formatDate(value?: string | null): string {
  const date = parseDateTime(value)
  if (!date) return '—'
  return `${date.getFullYear()}-${pad2(date.getMonth() + 1)}-${pad2(date.getDate())}`
}

/** 时间戳：同年 MM-DD HH:mm；跨年 YYYY-MM-DD HH:mm */
export function formatDateTime(value?: string | null, now: Date = new Date()): string {
  const date = parseDateTime(value)
  if (!date) return '—'
  const time = `${pad2(date.getHours())}:${pad2(date.getMinutes())}`
  if (date.getFullYear() === now.getFullYear()) {
    return `${pad2(date.getMonth() + 1)}-${pad2(date.getDate())} ${time}`
  }
  return `${formatDate(value)} ${time}`
}

/** 通知列表 24 小时内用相对时间 */
export function relativeTime(value?: string | null, now: Date = new Date()): string {
  const date = parseDateTime(value)
  if (!date) return '—'
  const diffMs = now.getTime() - date.getTime()
  if (diffMs < 0) return formatDateTime(value, now)
  const minutes = Math.floor(diffMs / 60000)
  if (minutes < 1) return '刚刚'
  if (minutes < 60) return `${minutes} 分钟前`
  const hours = Math.floor(minutes / 60)
  if (hours < 24) return `${hours} 小时前`
  const days = Math.floor(hours / 24)
  if (days < 7) return `${days} 天前`
  return formatDateTime(value, now)
}

/** 千分位仅在 ≥10000 时使用（12,480） */
export function formatNumber(value?: number | null): string {
  if (value === null || value === undefined || Number.isNaN(value)) return '0'
  const abs = Math.abs(value)
  if (abs >= 10000) return value.toLocaleString('en-US')
  return String(value)
}

/** 分母为 0 的比率显示 —（不是 0%）；百分比 1 位小数 */
export function formatPercent(rate?: number | null, digits = 1): string {
  if (rate === null || rate === undefined || Number.isNaN(rate)) return '—'
  const percent = rate <= 1 ? rate * 100 : rate
  return `${percent.toFixed(digits)}%`
}

/** 催办文案：「已催办 N 次 · 最近 M 天前」 */
export function formatUrgeSummary(urgeCount?: number | null, lastUrgedAt?: string | null, now: Date = new Date()): string {
  const count = urgeCount ?? 0
  if (count <= 0) return '未催办'
  const last = lastUrgedAt ? relativeTime(lastUrgedAt, now).replace('前', '前') : ''
  return last ? `已催办 ${count} 次 · 最近 ${last}` : `已催办 ${count} 次`
}

export function channelLabel(status?: ChannelStatus | string | null, fallback?: string | null): string {
  if (fallback) return fallback
  if (status && status in CHANNEL_LABELS) return CHANNEL_LABELS[status as ChannelStatus]
  return '站内'
}

/** 姓名首字（OwnerChip 头像兜底） */
export function initialOf(name?: string | null): string {
  const text = (name ?? '').trim()
  return text ? text.slice(0, 1) : '?'
}

export function truncate(text: string | null | undefined, max: number): string {
  if (!text) return ''
  return text.length > max ? `${text.slice(0, max)}…` : text
}
