import { fileURLToPath, URL } from 'node:url'

import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'

// https://vitejs.dev/config/
export default defineConfig({
  plugins: [
    vue(),
  ],
  resolve: {
    alias: {
      '@': fileURLToPath(new URL('./src', import.meta.url))
    }
  },
  server: {
    port: 3000,
    proxy: {
      '/api': {
        // control-service 地址；远端环境用 VITE_PROXY_TARGET 覆盖
        // （如 ssh -L 18085:localhost:8085 后指向 http://localhost:18085）
        target: process.env.VITE_PROXY_TARGET || 'http://localhost:8085',
        changeOrigin: true
      }
    }
  }
})