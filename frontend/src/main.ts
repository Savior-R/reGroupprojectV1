import { createApp } from 'vue'
import { createPinia } from 'pinia'
import ElementPlus from 'element-plus'
import 'element-plus/dist/index.css'
import 'element-plus/theme-chalk/dark/css-vars.css'
import './style.css'
import App from './App.vue'
import { router } from './app/router'
import { useAuthStore } from './stores/auth'

document.documentElement.classList.add('dark')

const app = createApp(App)
app.use(createPinia())

// 刷新页面后，M2/M3 用的身份头要从已保存的 JWT 还原，否则会退回 dev 预设账号。
useAuthStore().syncFromToken()

app.use(router).use(ElementPlus).mount('#app')
