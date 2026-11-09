/**
 * 筛选条件 ↔ URL（DESIGN §4.2 / AC-8：刷新、收藏、分享后筛选必须还在）。
 *
 * 只做三件事：从 route.query 解析出强类型状态、把状态写回 URL、外部导航（前进/后退）时回灌状态。
 * 解析与序列化故意导出为纯函数，方便单测直接钉住「URL 往返」。
 */
import { computed, ref, watch, type ComputedRef, type Ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import type { LocationQuery, LocationQueryRaw } from 'vue-router'

export type QueryKind = 'string' | 'number' | 'boolean'
export type QueryKinds<T extends object> = Partial<Record<Extract<keyof T, string>, QueryKind>>

function rawValue(value: unknown): string | null {
  if (typeof value === 'string') return value
  if (Array.isArray(value) && typeof value[0] === 'string') return value[0]
  return null
}

function kindOf(value: unknown): QueryKind {
  if (typeof value === 'number') return 'number'
  if (typeof value === 'boolean') return 'boolean'
  return 'string'
}

function decode(kind: QueryKind, raw: string): unknown {
  if (kind === 'boolean') {
    if (raw === 'true' || raw === '1') return true
    if (raw === 'false' || raw === '0') return false
    return undefined
  }
  if (kind === 'number') {
    const parsed = Number(raw)
    return Number.isFinite(parsed) ? parsed : undefined
  }
  return raw
}

/** URL → 状态：非法值一律落回默认值，不抛异常（手改 URL 不应白屏） */
export function parseQuery<T extends object>(
  defaults: T,
  query: LocationQuery,
  kinds: QueryKinds<T> = {},
): T {
  const result = { ...defaults } as unknown as Record<string, unknown>
  const kindMap = kinds as Record<string, QueryKind>
  for (const key of Object.keys(defaults)) {
    const raw = rawValue(query[key])
    if (raw === null || raw === '') continue
    const kind = kindMap[key] ?? kindOf((defaults as Record<string, unknown>)[key])
    const value = decode(kind, raw)
    if (value !== undefined) result[key] = value
  }
  return result as T
}

/** 状态 → URL：与默认值相同的键不写，URL 只留真正生效的筛选 */
export function toQuery<T extends object>(state: T, defaults: T): Record<string, string> {
  const out: Record<string, string> = {}
  for (const key of Object.keys(defaults)) {
    const value = (state as Record<string, unknown>)[key]
    const fallback = (defaults as Record<string, unknown>)[key]
    if (value === undefined || value === null || value === '') continue
    if (value === fallback) continue
    out[key] = String(value)
  }
  return out
}

function sameQuery(current: LocationQuery, next: LocationQueryRaw): boolean {
  const keys = new Set([...Object.keys(current), ...Object.keys(next)])
  for (const key of keys) {
    const a = rawValue(current[key])
    const rawNext = next[key]
    const b = rawNext === undefined || rawNext === null ? null : String(rawNext)
    if (a !== b) return false
  }
  return true
}

export interface QuerySync<T extends object> {
  state: Ref<T>
  /** 把当前筛选写回 URL（替换历史记录，避免每敲一个字符多一条返回记录） */
  apply: () => void
  reset: () => void
  /** 生效中的筛选个数（不含分页），用于「清除全部」的显隐与 chips */
  activeCount: ComputedRef<number>
}

export function useQuerySync<T extends object>(
  defaults: T,
  kinds: QueryKinds<T> = {},
): QuerySync<T> {
  const route = useRoute()
  const router = useRouter()
  const state = ref(parseQuery(defaults, route.query, kinds)) as Ref<T>

  // 浏览器前进/后退或手改 URL：以 URL 为准
  watch(
    () => route.query,
    (query) => {
      state.value = parseQuery(defaults, query, kinds)
    },
  )

  function apply(): void {
    const encoded = toQuery(state.value, defaults)
    const next: LocationQueryRaw = { ...route.query }
    for (const key of Object.keys(defaults)) {
      if (key in encoded) next[key] = encoded[key]
      else delete next[key]
    }
    if (sameQuery(route.query, next)) return
    void router.replace({ query: next })
  }

  function reset(): void {
    state.value = { ...defaults }
    apply()
  }

  const activeCount = computed(() => Object.keys(toQuery(state.value, defaults)).length)

  return { state, apply, reset, activeCount }
}
