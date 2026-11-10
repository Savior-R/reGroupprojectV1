import { createApp } from 'vue'
import { createPinia } from 'pinia'
import ElementPlus from 'element-plus'
import 'element-plus/dist/index.css'
import './style.css'
import App from './App.vue'
import { router } from './app/router'

// 主题在 style.css 中统一为浅色（米白底 + 墨绿主色），
// 不要再给 <html> 加 dark 类，也不要引入 dark/css-vars.css。
createApp(App).use(createPinia()).use(router).use(ElementPlus).mount('#app')
