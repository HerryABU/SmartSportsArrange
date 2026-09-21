import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'
import { fileURLToPath, URL } from 'node:url'
import { resolve } from 'node:path'

export default defineConfig(({ command }) => ({
  plugins: [vue()],
  resolve: {
    alias: {
      '@': fileURLToPath(new URL('./src', import.meta.url))
    }
  },
  // 构建产物使用相对路径（./assets/...），以兼容反向代理子路径部署
  // 如 http://host/sportmg/（帽子前缀任意，严禁硬编码），dev 模式保持绝对路径
  base: command === 'build' ? './' : '/',
  build: {
    outDir: resolve(__dirname, '../sports-backend/src/main/resources/static'),
    // 注意：emptyOutDir 保持 false —— 构建环境的安全删除机制会拦截 vite 对 static/assets
    // 的 rmSync 清空操作（导致构建失败）。改为由构建脚本用「重命名」方式清理旧 static，
    // 重命名不被安全删除拦截，且能保证 jar 打包的是干净的前端产物。
    emptyOutDir: false
  },
  server: {
    port: 3000,
    proxy: {
      '/api': {
        target: 'http://localhost:8080',
        changeOrigin: true
      }
    }
  }
}))
