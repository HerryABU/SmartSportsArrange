import { createApp } from 'vue'
import { createPinia } from 'pinia'
import ElementPlus from 'element-plus'
import 'element-plus/dist/index.css'
import zhCn from 'element-plus/es/locale/lang/zh-cn'
import * as ElementPlusIconsVue from '@element-plus/icons-vue'
import App from './App.vue'
import router from './router'
import { normalizeHashEntry } from '@/utils/base'

// 入口归一化：把 /login#/teacher/dashboard 这类「文档路径被钉在子路径」的错误 URL
// 自动重定向到根路径 + hash（/#/teacher/dashboard）。若触发重定向，当前页面会重新加载，
// 故必须放在 createApp 之前。
normalizeHashEntry()

const app = createApp(App)
app.use(createPinia())
app.use(router)
app.use(ElementPlus, { locale: zhCn })
for (const [key, component] of Object.entries(ElementPlusIconsVue)) {
  app.component(key, component)
}
app.mount('#app')
