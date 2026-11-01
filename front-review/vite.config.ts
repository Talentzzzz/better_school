import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'
import { fileURLToPath, URL } from 'node:url'

// 审核中台独立工程（票 12，spec §5.4）：
//  - 开发端口独立 5174（主前端 5173），/api 与审核长连接 /ws/review 代理到主后端 8080（ws: true）
//  - 生产独立构建，静态产物发到主站同一域名的 /review/ 子路径（内网），故 base=/review/
//    （同域名子路径下与主站共享 localStorage 登录态，不引入跨域与登录态搬运）
export default defineConfig(({ mode }) => ({
  base: mode === 'production' ? '/review/' : '/',
  plugins: [vue()],
  resolve: {
    alias: {
      '@': fileURLToPath(new URL('./src', import.meta.url))
    }
  },
  css: {
    preprocessorOptions: {
      scss: {
        // 与 front/vite.config.ts 同款：每个 SFC 编译单元自动注入变量，variables.scss 自身除外
        additionalData: (source: string, filename?: string) => {
          if (filename?.endsWith('variables.scss')) return source
          return `@use "@/styles/variables.scss" as *;\n${source}`
        }
      }
    }
  },
  server: {
    port: 5174,
    host: true,
    proxy: {
      '/api': {
        target: 'http://localhost:8080',
        changeOrigin: true
      },
      '/ws/review': {
        target: 'http://localhost:8080',
        ws: true
      }
    }
  }
}))
