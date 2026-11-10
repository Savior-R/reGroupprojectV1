import { createApp } from 'vue'
import { createPinia } from 'pinia'
import ElementPlus from 'element-plus'
import 'element-plus/dist/index.css'
import 'element-plus/theme-chalk/dark/css-vars.css'
import '../../style.css'
import { apiClient } from '../../api/client'
import { installMockApi } from './mockApi'
import PreviewPage from './PreviewPage.vue'

document.documentElement.classList.add('dark')
installMockApi(apiClient)

createApp(PreviewPage).use(createPinia()).use(ElementPlus).mount('#app')
