import { createApp } from 'vue'
import { createPinia } from 'pinia'
import App from './App.vue'
import router from './router'
import { setUnauthorizedHandler } from './api/http'
import { useAuthStore } from './stores/auth'
import './styles/tokens.css'
import './styles/base.css'

const app = createApp(App)
const pinia = createPinia()

app.use(pinia)
app.use(router)

/**
 * 会话失效的统一出口（http 层不 import store，避免循环依赖）：
 * 清空本地身份 → 带 next 回登录页。已在登录页/回调页时只清身份，不再制造跳转循环。
 */
setUnauthorizedHandler(() => {
  const auth = useAuthStore(pinia)
  auth.clear()

  const current = router.currentRoute.value
  if (current.path === '/login' || current.path === '/sso/callback') return

  void router.replace({
    path: '/login',
    query: current.fullPath && current.fullPath !== '/' ? { next: current.fullPath } : {},
  })
})

app.mount('#app')
