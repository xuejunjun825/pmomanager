/**
 * 路由表 + 访问守卫（TECHNICAL §6.1；DESIGN §2.2）
 *
 * 页面组件按文件名延迟解析：T08/T09/T10 的页面在后续任务里落地，
 * 本文件不需要再改一次；缺失时渲染占位页，构建不会因 import 不存在的文件而失败。
 *
 * 守卫顺序固定：ensureLoaded → 未登录 → 角色 → （改密模态由 AppShell 负责）。
 */
import { defineComponent, h } from 'vue'
import { createRouter, createWebHistory, type RouteRecordRaw, type Router } from 'vue-router'
import { useAuthStore } from '@/stores/auth'
import { safeInternalPath } from '@/utils/permissions'
import type { Role } from '@/types/enums'

/** 尚未交付的页面：明确告诉用户「此处待建设」，而不是白屏 */
const ComingSoon = defineComponent({
  name: 'ComingSoonPage',
  props: { title: { type: String, default: '' } },
  setup(props) {
    return () =>
      h('div', { style: { padding: '48px 0', textAlign: 'center', color: '#667085' } }, [
        h('h1', { style: { fontSize: '20px', color: '#0F172A', margin: '0 0 8px' } }, props.title || '页面建设中'),
        h('p', { style: { margin: 0, fontSize: '13px' } }, '该页面将在后续任务中交付。'),
      ])
  },
})

const modules = import.meta.glob('../pages/**/*Page.vue') as Record<string, () => Promise<unknown>>

/** 按文件名解析页面组件；未找到时退回占位页 */
function page(name: string) {
  const key = Object.keys(modules).find((path) => path.endsWith(`/${name}.vue`))
  if (key) return modules[key] as () => Promise<{ default: unknown }>
  return () => Promise.resolve({ default: ComingSoon })
}

export const routes: RouteRecordRaw[] = [
  {
    path: '/login',
    name: 'login',
    component: page('LoginPage'),
    meta: { public: true, title: '登录' },
  },
  {
    path: '/sso/callback',
    name: 'sso-callback',
    component: page('SsoCallbackPage'),
    meta: { public: true, title: '钉钉登录' },
  },
  {
    path: '/',
    name: 'home',
    component: ComingSoon,
    props: { title: '首页' },
    meta: { title: '首页' },
  },
  {
    path: '/dashboard',
    name: 'dashboard',
    component: page('DashboardPage'),
    meta: { title: '督办看板', section: '工作台', roles: ['ADMIN', 'EXEC'] },
  },
  {
    path: '/matrix',
    name: 'matrix',
    component: page('MatrixPage'),
    meta: { title: '闭环矩阵', section: '工作台', roles: ['ADMIN', 'EXEC'] },
  },
  {
    path: '/gantt',
    name: 'gantt',
    component: page('GanttPage'),
    meta: { title: '进度甘特', section: '工作台', roles: ['ADMIN', 'EXEC'] },
  },
  {
    path: '/tasks',
    name: 'tasks',
    component: page('TaskPoolPage'),
    meta: { title: '任务池', section: '任务', roles: ['ADMIN', 'EXEC'] },
  },
  {
    path: '/tasks/:id(\\d+)',
    name: 'task-detail',
    component: page('TaskDetailPage'),
    meta: { title: '任务详情', section: '任务' },
  },
  {
    path: '/my',
    name: 'my-tasks',
    component: page('MyTasksPage'),
    meta: { title: '我的任务', section: '任务', roles: ['ADMIN', 'OWNER'] },
  },
  {
    path: '/meetings',
    name: 'meetings',
    component: page('MeetingsPage'),
    meta: { title: '会议与决议', section: '会议', roles: ['ADMIN'] },
  },
  {
    path: '/meetings/:id(\\d+)',
    name: 'meeting-detail',
    component: page('MeetingDetailPage'),
    meta: { title: '会议详情', section: '会议', roles: ['ADMIN'] },
  },
  {
    path: '/notifications',
    name: 'notifications',
    component: page('NotificationsPage'),
    meta: { title: '通知', section: '通知' },
  },
  {
    path: '/users',
    name: 'users',
    component: page('UsersPage'),
    meta: { title: '用户与角色', section: '系统', roles: ['ADMIN'] },
  },
  {
    path: '/settings',
    name: 'settings',
    component: page('SettingsPage'),
    meta: { title: '系统设置', section: '系统', roles: ['ADMIN'] },
  },
  {
    path: '/403',
    name: 'forbidden',
    component: page('ForbiddenPage'),
    meta: { public: true, title: '无访问权限' },
  },
  {
    path: '/503',
    name: 'unavailable',
    component: page('UnavailablePage'),
    meta: { public: true, title: '服务不可用' },
  },
  {
    path: '/500',
    name: 'server-error',
    component: page('ServerErrorPage'),
    meta: { public: true, title: '服务异常' },
  },
  {
    path: '/404',
    name: 'not-found-explicit',
    component: page('NotFoundPage'),
    meta: { public: true, title: '页面不存在' },
  },
  {
    path: '/:pathMatch(.*)*',
    name: 'not-found',
    component: page('NotFoundPage'),
    meta: { public: true, title: '页面不存在' },
  },
]

export const router = createRouter({
  history: createWebHistory(),
  routes,
  scrollBehavior(to, _from, savedPosition) {
    if (savedPosition) return savedPosition
    if (to.hash) return { el: to.hash, behavior: 'smooth' }
    return { top: 0 }
  },
})

/**
 * 守卫单独注册（测试用内存路由复用同一份规则，避免测一份、跑一份）。
 * 顺序：ensureLoaded → 公开页放行 → 未登录回登录页 → 角色校验（改密模态由 AppShell 负责）。
 */
export function registerGuards(target: Router): void {
  target.beforeEach(async (to) => {
  const auth = useAuthStore()

  // 先完成一次身份探测：保证刷新后 role 可用，且公开页也能拿到 siteName/钉钉配置
  if (!auth.loaded) await auth.ensureLoaded()

  const isPublic = to.meta.public === true

  if (isPublic) {
    // 已登录再访问登录页：按 next 或落地页跳走（不把已登录用户困在表单里）
    if (to.name === 'login' && auth.isAuthenticated) {
      const next = safeInternalPath(to.query.next)
      return next ?? auth.landingPath()
    }
    return true
  }

  // 根路径：按角色落地（ADMIN→/tasks、OWNER→/my、EXEC→/dashboard）
  if (to.path === '/') {
    return auth.isAuthenticated ? auth.landingPath() : { path: '/login' }
  }

  if (!auth.isAuthenticated) {
    return { path: '/login', query: to.fullPath === '/' ? {} : { next: to.fullPath } }
  }

  const roles = to.meta.roles as Role[] | undefined
  if (roles && auth.role && !roles.includes(auth.role)) {
    return { path: '/403' }
  }

  return true
  })

  target.afterEach((to) => {
    const auth = useAuthStore()
    const title = (to.meta.title as string | undefined) ?? ''
    document.title = title && auth.siteName ? `${title} · ${auth.siteName}` : auth.siteName || '会议任务督办系统'
  })
}

registerGuards(router)

export default router
