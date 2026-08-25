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
    emptyOutDir: true
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
