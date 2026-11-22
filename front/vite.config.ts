import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'
import { fileURLToPath, URL } from 'node:url'

export default defineConfig({
  plugins: [vue()],
  resolve: {
    alias: {
      '@': fileURLToPath(new URL('./src', import.meta.url))
    }
  },
  css: {
    preprocessorOptions: {
      scss: {
        // 每个 scss 编译单元（含 SFC 内联 style）自动注入变量，variables.scss 自身除外
        additionalData: (source: string, filename?: string) => {
          if (filename?.endsWith('variables.scss')) return source
          return `@use "@/styles/variables.scss" as *;\n${source}`
        }
      }
    }
  },
  server: {
    port: 5173,
    host: true,
    proxy: {
      // 营销前缀必须排在 '/api' 之前：Vite 的 proxy 按 key 插入顺序匹配，'*' 会吃掉一切。
      // 服务端 controller 映射在 /api/<域>/**，故不 rewrite（前缀即真实路径的一部分）；
      // points / lottery 两域同属营销服务（marketing-app，8081）。已下线域的代理随票 09/16 一并删除。
      '/api/points': {
        target: 'http://localhost:8081',
        changeOrigin: true
      },
      '/api/lottery': {
        target: 'http://localhost:8081',
        changeOrigin: true
      },
      '/api': {
        target: 'http://localhost:8080',
        changeOrigin: true
      },
      '/ws': {
        target: 'http://localhost:8080',
        ws: true
      }
    }
  }
})
