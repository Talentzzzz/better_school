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
      // 单体收口后只有一个后端：服务端 controller 映射在 /api/<域>/**，
      // 故不 rewrite（前缀即真实路径的一部分）。营销前缀分流规则已随票 08 删除。
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
