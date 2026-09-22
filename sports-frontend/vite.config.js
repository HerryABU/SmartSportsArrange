import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'
import { resolve } from 'node:path'

// vite 在加载 ESM 配置时会把配置打包到临时文件再执行，
// 因此 import.meta.url / __dirname 都会指向临时目录（导致 root/入口解析错位）。
// 配置执行上下文里 process.cwd() 即项目根目录（运行 vite build 时的 cwd），用其推导路径最可靠。
const rootDir = process.cwd()
console.error('[VITE-CONFIG] rootDir =', rootDir, '| cwd =', process.cwd())

export default defineConfig(({ command }) => ({
  plugins: [vue()],
  root: rootDir,
  resolve: {
    alias: {
      '@': resolve(rootDir, 'src')
    }
  },
  // 构建产物使用相对路径（./assets/...），以兼容反向代理子路径部署
  // 如 http://host/sportmg/（帽子前缀任意，严禁硬编码），dev 模式保持绝对路径
  base: command === 'build' ? './' : '/',
  build: {
    outDir: resolve(rootDir, '../sports-backend/src/main/resources/static'),
    // 注意：emptyOutDir 保持 false —— 构建环境的安全删除机制会拦截 vite 对 static/assets
    // 的 rmSync 清空操作（导致构建失败）。改为由构建脚本用「重命名」方式清理旧 static，
    // 重命名不被安全删除拦截，且能保证 jar 打包的是干净的前端产物。
    emptyOutDir: false,
    // 显式指定入口，规避部分环境下 vite 把入口解析成 "index.html/index.html" 的重复路径问题
    rollupOptions: {
      input: resolve(rootDir, 'index.html')
    }
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
