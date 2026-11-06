import { createApp } from 'vue'
import { createPinia } from 'pinia'
import ElementPlus from 'element-plus'
import 'element-plus/dist/index.css'
import 'element-plus/theme-chalk/dark/css-vars.css'
import './style.css'
import App from './App.vue'
import { router } from './app/router'

document.documentElement.classList.add('dark')

createApp(App).use(createPinia()).use(router).use(ElementPlus).mount('#app')
