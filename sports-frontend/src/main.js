import { createApp } from 'vue'
import { createPinia } from 'pinia'
import ElementPlus from 'element-plus'
import 'element-plus/dist/index.css'
// Element Plus 暗色主题：随 <html>.dark 切换（明暗功能依赖此文件，
// 否则 el-tabs/卡片/输入框等组件在暗色模式下仍保持浅色）。须置于 index.css 之后。
import 'element-plus/theme-chalk/dark/css-vars.css'
// 角色主题：管理员 / 体育老师 / 班主任 / 裁判 / 学生 统一视觉语言
import '@/styles/role-theme.css'
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
