import { createApp } from 'vue'
import { createPinia } from 'pinia'
import ElementPlus from 'element-plus'
import 'element-plus/dist/index.css'
import './style.css'
import App from './App.vue'
import { router } from './app/router'
import { useAuthStore } from './stores/auth'

// 主题在 style.css 中统一为浅色（米白底 + 墨绿主色），
// 不要再给 <html> 加 dark 类，也不要引入 dark/css-vars.css。
const app = createApp(App)
const pinia = createPinia()
app.use(pinia).use(router).use(ElementPlus)
// 页面刷新后按本地 JWT 同步一次 M2/M3 请求头身份（见 stores/auth.ts）。
useAuthStore(pinia).syncFromToken()
app.mount('#app')
